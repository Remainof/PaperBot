package org.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * DashScope API 超时配置
 */
@Configuration
public class DashScopeConfig {

    @Value("${spring.ai.dashscope.chat.options.timeout:180000}")
    private long timeout;

    @Bean
    public RestClient.Builder restClientBuilder() {
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMillis(timeout));
        return RestClient.builder().requestFactory(factory);
    }
}
