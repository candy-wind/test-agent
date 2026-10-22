package vip.mate.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * test-agent 启动类
 */
@EnableScheduling
@SpringBootApplication
public class TestAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestAgentApplication.class, args);
    }
}