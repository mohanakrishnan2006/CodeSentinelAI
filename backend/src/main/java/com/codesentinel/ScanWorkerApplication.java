package com.codesentinel;

import com.codesentinel.config.SecurityConfig;
import com.codesentinel.security.JwtAuthFilter;
import com.codesentinel.service.AuthService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = "com.codesentinel",
        excludeFilters = {
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = {
                                CodeSentinelApplication.class,
                                SecurityConfig.class,
                                AuthService.class,
                                JwtAuthFilter.class
                        }),
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.codesentinel\\.controller\\..*")
        })
public class ScanWorkerApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ScanWorkerApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }
}
