package com.hc.lifemap.media;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 媒体服务：照片上传、EXIF 方向纠正、WebP 与缩略图生成、对象存储读写、孤儿照片对账。
 * <p>P0 阶段只保证骨架可启动、Flyway 基线可执行；上传链路在 P2 落地。</p>
 */
@ConfigurationPropertiesScan
@EnableDiscoveryClient
@SpringBootApplication
public class MediaServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaServiceApplication.class, args);
    }
}
