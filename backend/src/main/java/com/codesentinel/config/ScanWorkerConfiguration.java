package com.codesentinel.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "codesentinel.scan-worker.enabled", havingValue = "true")
public class ScanWorkerConfiguration {
}
