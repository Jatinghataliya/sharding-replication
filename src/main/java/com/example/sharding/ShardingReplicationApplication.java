package com.example.sharding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class ShardingReplicationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShardingReplicationApplication.class, args);
    }
}
