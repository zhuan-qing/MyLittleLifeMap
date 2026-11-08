package com.hc.lifemap.place.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 装配：注册分页内层拦截器。
 * <p>缺少该 Bean 时 {@code selectPage} 不会拼 {@code LIMIT}，结果退化成全表查询。</p>
 * <p>分页插件自 3.5.9 起迁到独立模块 {@code mybatis-plus-jsqlparser}，
 * 只引 starter 会在启动时报 {@code ClassNotFoundException: PaginationInnerInterceptor}。</p>
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}
