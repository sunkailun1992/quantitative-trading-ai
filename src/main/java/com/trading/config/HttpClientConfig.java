package com.trading.config;

import org.springframework.context.annotation.Bean;                 // 声明Bean注解
import org.springframework.context.annotation.Configuration;      // 声明配置类
import org.springframework.web.reactive.function.client.WebClient; // 响应式Web客户端
import java.net.http.HttpClient;                                  // Java原生HttpClient
import java.time.Duration;                                        // 设置超时时间

/**
 * 🌐 HTTP客户端配置类
 * -----------------------------------------------------
 * ✅ 兼容Spring Boot 3.x
 * ✅ 移除 Apache HttpClient 依赖
 * ✅ 提供全局 WebClient Bean + Java HttpClient Bean
 */
@Configuration
public class HttpClientConfig {

    /**
     * 🧩 提供 Java 17 原生 HttpClient Bean
     * 用于同步或签名请求（被 HttpUtil 使用）
     */
    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMinutes(10))      // ✅ 连接超时：10分钟
                .followRedirects(HttpClient.Redirect.NORMAL) // 自动跟随重定向
                .build();                                   // 构建 HttpClient 实例
    }

    /**
     * 💡 提供全局 WebClient Bean
     * 用于响应式接口（例如 DeepSeek AI API 调用）
     */
    @Bean
    public WebClient webClient() {
        return WebClient.builder()
                .baseUrl("https://api.deepseek.com") // 默认基础URL（可换）
                .defaultHeader("Content-Type", "application/json") // 默认请求头
                .build();
    }
}
