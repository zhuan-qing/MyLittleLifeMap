package com.hc.lifemap.media.storage;

import com.hc.lifemap.media.config.S3Properties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;

/**
 * 对象存储门面：存 / 取预签名地址 / 删 / 查存在，四件事。
 *
 * <p>桶是私有的，前端拿到的地址必须由后端签出来，不能自己拼 {@code endpoint + key}。
 * SDK 异常统一包成 {@link StorageException}，调用方不必认识 {@code S3Exception} 那一套。</p>
 */
@Component
public class S3Storage {

    private final S3Client client;
    private final S3Presigner presigner;
    private final S3Properties properties;

    public S3Storage(S3Client client, S3Presigner presigner, S3Properties properties) {
        this.client = client;
        this.presigner = presigner;
        this.properties = properties;
    }

    /**
     * 写入对象，返回原 key（调用方要拿它落库）。
     *
     * @param size 从 {@code stream} 能读到的字节数，必须准确——S3 的签名会校验长度
     */
    public String put(String key, InputStream stream, long size, String contentType) {
        try {
            client.putObject(PutObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .contentType(contentType)
                    .build(), RequestBody.fromInputStream(stream, size));
            return key;
        } catch (Exception e) {
            throw new StorageException("写入对象失败: " + key, e);
        }
    }

    /** 带签名的临时 GET 地址，有效期取 {@code lifemap.storage.s3.presign-ttl}。 */
    public String presignedGet(String key) {
        try {
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(properties.presignTtl())
                            .getObjectRequest(GetObjectRequest.builder()
                                    .bucket(properties.bucket())
                                    .key(key)
                                    .build())
                            .build())
                    .url()
                    .toString();
        } catch (Exception e) {
            throw new StorageException("生成预签名地址失败: " + key, e);
        }
    }

    /** 删除对象；对象本来就不存在时 S3 返回 204，天然幂等。 */
    public void remove(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
        } catch (Exception e) {
            throw new StorageException("删除对象失败: " + key, e);
        }
    }

    /**
     * 对象是否存在。只有「对象不存在」返回 false——桶不存在、凭据错、网络不通都是真故障，
     * 一律抛出去，别让配置错误伪装成"文件没传上来"。
     */
    public boolean exists(String key) {
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new StorageException("查询对象失败: " + key, e);
        } catch (Exception e) {
            throw new StorageException("查询对象失败: " + key, e);
        }
    }

    /** 对象存储读写失败；上传链路（P2-T02）据此映射成 20501 对象存储读写失败。 */
    public static class StorageException extends RuntimeException {

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
