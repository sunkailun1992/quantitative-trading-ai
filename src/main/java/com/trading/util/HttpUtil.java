package com.trading.util;

import com.fasterxml.jackson.databind.ObjectMapper; // JSON处理
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;                                 // URI 构建
import java.net.http.HttpClient;                    // Java 17 自带 HttpClient
import java.net.http.HttpRequest;                   // HTTP 请求对象
import java.net.http.HttpResponse;                  // HTTP 响应对象
import java.nio.charset.StandardCharsets;           // UTF-8 编码
import java.time.Duration;                          // 超时控制
import java.util.HashMap;                           // Map 用于签名参数
import java.util.Map;
import java.util.stream.Collectors;                 // 参数拼接工具

/**
 * 🌐 HTTP工具类
 * -------------------------------------------------
 * ✅ 方法名称与参数签名完全保留原状
 * ✅ 去除 Apache HttpClient 依赖
 * ✅ 使用 Java 17 原生 HttpClient
 * ✅ 每行带详细注释
 */
@Slf4j
@Component
public class HttpUtil {

    private final ObjectMapper objectMapper;                 // JSON序列化工具
    private final BybitSignatureUtil signatureUtil;          // Bybit签名工具
    private final BybitSignatureUtil bybitSignatureUtil;     // 第二个签名工具（保留兼容性）

    /**
     * 构造函数依赖注入
     */
    public HttpUtil(ObjectMapper objectMapper,
                    BybitSignatureUtil signatureUtil,
                    BybitSignatureUtil bybitSignatureUtil) {
        this.objectMapper = objectMapper;
        this.signatureUtil = signatureUtil;
        this.bybitSignatureUtil = bybitSignatureUtil;
    }

    // =========================================================
    // 🟢 公开API GET请求（无认证）
    // =========================================================
    public String publicGet(String url) throws Exception {
        try {
            log.debug("🌐 公开API GET请求: {}", url);

            // 1️⃣ 构建请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))                       // 设置URL
                    .GET()                                      // GET方法
                    .timeout(Duration.ofSeconds(10))            // 超时10秒
                    .build();

            // 2️⃣ 执行请求
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            // 3️⃣ 打印响应
            log.debug("📡 响应状态: {}", response.statusCode());
            log.debug("📨 响应体: {}", response.body());

            // 4️⃣ 检查状态码
            if (response.statusCode() != 200) {
                throw new RuntimeException("公开API请求失败: " + response.statusCode());
            }

            return response.body();
        } catch (Exception e) {
            log.error("❌ 公开API GET异常: {}", e.getMessage());
            throw e;
        }
    }

    // =========================================================
    // 🔵 普通 GET 请求
    // =========================================================
    public String sendGetRequest(String url) throws Exception {
        try {
            log.debug("🌐 发送GET请求: {}", url);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))                   // 请求URL
                    .GET()                                  // GET方法
                    .timeout(Duration.ofSeconds(10))        // 超时
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            log.info("📡 HTTP响应详情: 状态码={}  响应体={}", response.statusCode(), response.body());

            if (response.statusCode() != 200) {
                throw new RuntimeException("GET请求失败: " + response.statusCode());
            }

            return response.body();
        } catch (Exception e) {
            log.error("❌ GET请求异常: {}", e.getMessage());
            throw e;
        }
    }

    // =========================================================
    // 🟠 普通 POST 请求
    // =========================================================
    public String sendPostRequest(String url, String requestBody) throws Exception {
        try {
            log.debug("🌐 发送POST请求: {}", url);
            log.debug("📦 请求体: {}", requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))                           // URL
                    .header("Content-Type", "application/json")     // JSON头
                    .timeout(Duration.ofSeconds(10))                // 超时
                    .POST(HttpRequest.BodyPublishers.ofString(
                            requestBody != null ? requestBody : "")) // body非空判断
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            log.info("📡 HTTP响应详情: 状态码={} 响应体={}", response.statusCode(), response.body());

            if (response.statusCode() != 200) {
                throw new RuntimeException("POST请求失败: " + response.statusCode());
            }

            return response.body();
        } catch (Exception e) {
            log.error("❌ POST请求异常: {}", e.getMessage());
            throw e;
        }
    }

    // =========================================================
    // 🔐 带认证头的 POST 请求
    // =========================================================
    public String sendAuthenticatedPost(String url, Map<String, String> headers, String requestBody) throws Exception {
        try {
            log.debug("🌐 发送认证POST请求: {}", url);

            // 1️⃣ 构造请求
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");

            // 2️⃣ 添加自定义头
            for (Map.Entry<String, String> header : headers.entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }

            // 3️⃣ 添加Body
            builder.POST(HttpRequest.BodyPublishers.ofString(requestBody != null ? requestBody : ""));

            // 4️⃣ 执行请求
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            log.info("📡 认证POST响应: 状态码={} 响应体={}", response.statusCode(), response.body());

            if (response.statusCode() != 200) {
                throw new RuntimeException("认证POST失败: " + response.statusCode());
            }

            return response.body();
        } catch (Exception e) {
            log.error("❌ 认证POST异常: {}", e.getMessage());
            throw e;
        }
    }

    // =========================================================
    // 🔑 带签名的 GET 请求（Bybit）
    // =========================================================
    public String signedGet(String baseUrl, String apiKey, String apiSecret, Map<String, String> params) throws Exception {
        long timestamp = System.currentTimeMillis(); // 当前时间戳
        String recvWindow = "5000";                  // 默认时间窗口

        // 1️⃣ 拼接 query 参数
        String queryString = params != null && !params.isEmpty()
                ? params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"))
                : "";

        // 2️⃣ 生成签名
        String sign = bybitSignatureUtil.generateSignature(apiSecret, timestamp, recvWindow, apiKey, params);

        // 3️⃣ 组装URL
        String url = queryString.isEmpty() ? baseUrl : baseUrl + "?" + queryString;

        // 4️⃣ 构建请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-BAPI-API-KEY", apiKey)
                .header("X-BAPI-SIGN", sign)
                .header("X-BAPI-TIMESTAMP", String.valueOf(timestamp))
                .header("X-BAPI-RECV-WINDOW", recvWindow)
                .header("Content-Type", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        // 5️⃣ 执行请求
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        log.info("📡 签名GET响应: 状态码={} 响应体={}", response.statusCode(), response.body());

        if (response.statusCode() != 200) {
            throw new RuntimeException("签名GET失败: " + response.statusCode());
        }

        return response.body();
    }

    // =========================================================
    // 🔒 带签名的 POST 请求（Bybit）
    // =========================================================
    public String signedPost(String baseUrl, String apiKey, String apiSecret, String jsonBody) throws Exception {
        long timestamp = System.currentTimeMillis(); // 当前时间戳
        String recvWindow = "5000";                  // 接收窗口

        // 1️⃣ 获取签名Header（内部封装了算法）
        Map<String, String> headers = signatureUtil.generatePostRequestSignature(apiKey, apiSecret, jsonBody);

        // 2️⃣ 构造请求
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");

        // 3️⃣ 添加认证头
        headers.forEach(builder::header);

        // 4️⃣ 设置POST body
        builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody != null ? jsonBody : ""));

        // 5️⃣ 执行请求
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        log.info("📡 签名POST响应: 状态码={} 响应体={}", response.statusCode(), response.body());

        if (response.statusCode() != 200) {
            throw new RuntimeException("签名POST失败: " + response.statusCode());
        }

        return response.body();
    }
}
