package dev.jaderss.lvr.mvc;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ExecutorConfig {

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService fanOutExecutor(
            @Value("${spring.threads.virtual.enabled}") boolean virtualThreads, BenchProperties properties) {
        return virtualThreads
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(
                        properties.fanoutThreads(),
                        Thread.ofPlatform().name("fanout-", 0).factory());
    }
}
