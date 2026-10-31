package com.leadinsight;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类：只装配，无业务逻辑。
 */
@SpringBootApplication
@MapperScan("com.leadinsight.**.mapper")
public class LeadInsightApplication {

    public static void main(String[] args) {
        SpringApplication.run(LeadInsightApplication.class, args);
    }
}
