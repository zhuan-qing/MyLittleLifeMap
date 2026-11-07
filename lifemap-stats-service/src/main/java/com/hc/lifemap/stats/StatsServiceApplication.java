package com.hc.lifemap.stats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 统计服务：从 place-service 拉 adcode 列表做省市聚合，结果缓存在 Redis（5 分钟）。
 * <p>无自有数据库——点亮是查询结果而非状态，不建点亮表。</p>
 */
@EnableDiscoveryClient
@EnableFeignClients
@SpringBootApplication
public class StatsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StatsServiceApplication.class, args);
    }
}
