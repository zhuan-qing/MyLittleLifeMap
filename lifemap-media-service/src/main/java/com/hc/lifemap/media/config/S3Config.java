package com.hc.lifemap.media.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * RustFS（S3 兼容）客户端装配：S3Client + S3Presigner，启动时确保桶存在，外加一个健康检查。
 *
 * <p>两处必须显式配、配错了表现各不相同：{@code forcePathStyle(true)}（否则 RustFS 返回 301）、
 * 预签名器自己的 {@code pathStyleAccessEnabled(true)}（漏了会签出 {@code bucket.127.0.0.1} 这种地址，
 * 访问直接 403）。</p>
 */
@Configuration
public class S3Config {

    private static final Logger log = LoggerFactory.getLogger(S3Config.class);

    /** RustFS 不校验区域，但 SigV4 签名必须带一个，固定 us-east-1。 */
    private static final Region REGION = Region.US_EAST_1;

    @Bean
    public S3Client s3Client(S3Properties properties) {
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(REGION)
                .credentialsProvider(credentials(properties))
                .forcePathStyle(true)
                .build();
        ensureBucket(client, properties);
        return client;
    }

    /** 预签名走独立的 presigner，寻址方式要再设一次——它不看 S3Client 的配置。 */
    @Bean
    public S3Presigner s3Presigner(S3Properties properties) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(REGION)
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    /**
     * 组件名取 Bean 名去掉 {@code HealthIndicator} 后缀，即 {@code /actuator/health} 里的 {@code s3}。
     * 只读探测：桶在 = UP，连不上 / 桶不在 = DOWN。
     */
    @Bean("s3HealthIndicator")
    public HealthIndicator s3HealthIndicator(S3Client client, S3Properties properties) {
        return () -> {
            try {
                return bucketExists(client, properties.bucket())
                        ? Health.up().withDetail("endpoint", properties.endpoint())
                                .withDetail("bucket", properties.bucket()).build()
                        : Health.down().withDetail("endpoint", properties.endpoint())
                                .withDetail("bucket", properties.bucket())
                                .withDetail("reason", "桶不存在").build();
            } catch (Exception e) {
                return Health.down(e)
                        .withDetail("endpoint", properties.endpoint())
                        .withDetail("bucket", properties.bucket())
                        .build();
            }
        };
    }

    private StaticCredentialsProvider credentials(S3Properties properties) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
    }

    /** 404 / NoSuchBucket 算"不存在"，其余异常继续往上抛。 */
    private boolean bucketExists(S3Client client, String bucket) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /**
     * 桶不存在时建一次（幂等）。失败只记 error 不拦启动：拦了连 {@code /actuator/health}
     * 都进不去，排查时反而少一个入口；此时 {@code s3} 组件会一直 DOWN，中间件恢复后重启本服务即可。
     */
    private void ensureBucket(S3Client client, S3Properties properties) {
        String bucket = properties.bucket();
        try {
            if (bucketExists(client, bucket)) {
                log.info("对象存储就绪: endpoint={} bucket={}", properties.endpoint(), bucket);
                return;
            }
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("对象存储桶不存在，已创建: endpoint={} bucket={}", properties.endpoint(), bucket);
        } catch (Exception e) {
            log.error("对象存储启动自检失败，桶 {} 未确认存在，照片相关接口在中间件恢复前不可用: {}",
                    bucket, e.toString());
        }
    }
}
