package com.hc.lifemap.place.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 装配：注册分页内层拦截器。
 *
 * <p>缺这个 Bean，{@code selectPage} 不会拼 {@code LIMIT}，退化成全表查询。分页插件自 3.5.9
 * 起迁到独立模块 {@code mybatis-plus-jsqlparser}，只引 starter 会启动即
 * {@code ClassNotFoundException: PaginationInnerInterceptor}。</p>
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
