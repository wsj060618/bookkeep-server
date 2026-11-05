package com.bookkeep.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Arrays;

@SpringBootApplication(scanBasePackages = "com.bookkeep")
@MapperScan("com.bookkeep.service.mapper")
// 回收站的到期清理靠 @Scheduled（见 BookPurgeJob）
@EnableScheduling
public class BookkeepApplication {

    private static final String RESET_PASSWORD = "--reset-password=";

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(BookkeepApplication.class);
        // 重置密码是单机运维动作：不启 Web 容器，端口被占用时也能执行，也不会多开一个对外入口（PLAN §8 第 24 条）
        if (Arrays.stream(args).anyMatch(arg -> arg.startsWith(RESET_PASSWORD))) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        app.run(args);
    }
}
