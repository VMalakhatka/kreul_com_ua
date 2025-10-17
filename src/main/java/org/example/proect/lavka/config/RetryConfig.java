package org.example.proect.lavka.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.example.proect.lavka.utils.RetryLoggingListener;
import org.springframework.retry.support.RetryTemplate;

@Configuration
@EnableRetry
public class RetryConfig {

    @Bean
    public RetryTemplate retryTemplate(RetryLoggingListener retryLoggingListener) {
        RetryTemplate template = new RetryTemplate();
        template.registerListener(retryLoggingListener);
        return template;
    }

}