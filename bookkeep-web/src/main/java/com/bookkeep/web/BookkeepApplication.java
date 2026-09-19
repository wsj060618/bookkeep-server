package com.bookkeep.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.bookkeep")
@MapperScan("com.bookkeep.service.mapper")
public class BookkeepApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookkeepApplication.class, args);
    }
}
