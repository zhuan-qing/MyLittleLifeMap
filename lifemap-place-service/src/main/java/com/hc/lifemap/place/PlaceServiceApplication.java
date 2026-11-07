package com.hc.lifemap.place;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 地点服务：地点 CRUD、时间轴、导出、高德 POI 与逆地理代理、WGS-84 → GCJ-02 坐标转换。
 * <p>P0 阶段只保证骨架可启动、Flyway 基线可执行；业务接口在 P1 落地。</p>
 */
@EnableDiscoveryClient
@SpringBootApplication
public class PlaceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlaceServiceApplication.class, args);
    }
}
