package com.bookkeep.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.bookkeep")
@MapperScan("com.bookkeep.service.mapper")
// 回收站的到期清理靠 @Scheduled（见 BookPurgeJob）
@EnableScheduling
public class BookkeepApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookkeepApplication.class, args);
    }
}