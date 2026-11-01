package com.bookkeep.web.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 * <p><strong>分页插件必须注册</strong>：不注册时 {@code selectPage} 不会报错，而是**静默不分页**
 * （SQL 里不拼 LIMIT，一次返回全表，total 还算得对）。这个坑排查成本很高——
 * 接口「看起来正常」，只是第 2 页和第 1 页数据一样。
 * <p>为什么要显式指定 {@link DbType#MYSQL}：分页方言要按数据库生成
 * （MySQL 是 {@code LIMIT ?,?}，Oracle 是 ROWNUM），不指定会走自动探测，多一次连接探测。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}