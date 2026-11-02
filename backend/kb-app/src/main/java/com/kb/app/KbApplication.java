package com.kb.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.kb")
@org.mybatis.spring.annotation.MapperScan(basePackages = "com.kb", annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class KbApplication {
    public static void main(String[] args) {
        SpringApplication.run(KbApplication.class, args);
    }
}
