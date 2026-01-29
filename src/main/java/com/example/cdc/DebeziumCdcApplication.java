package com.example.cdc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Debezium CDC RocketMQ 集成应用主启动类
 *
 * 功能：
 * - 使用 Embedded Debezium 3.0 监听 PostgreSQL 数据库变更
 * - 使用 Java 21 虚拟线程运行 Debezium 引擎
 * - 将变更事件推送到 RocketMQ 4.9.7
 * - 提供监控接口查询引擎状态和 LSN
 */
@SpringBootApplication
public class DebeziumCdcApplication {

    public static void main(String[] args) {
        SpringApplication.run(DebeziumCdcApplication.class, args);
    }
}
