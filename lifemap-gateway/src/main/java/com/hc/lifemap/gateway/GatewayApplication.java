package com.hc.lifemap.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 网关：全站唯一入口，负责路由、JWT 校验、限流与 traceId 生成。
 * <p>P0 阶段只保证骨架可启动、能注册到 Nacos、能探活；路由与鉴权在 P3 补齐。</p>
 */
@EnableDiscoveryClient
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
