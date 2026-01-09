package com.trading.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bybit V5 API签名工具 - 专门针对持仓查询修复
 */
@Slf4j
@Component
public class BybitSignatureUtil {
    private static final String HMAC_SHA256 = "HmacSHA256";
    /**
     * 生成签名（用于 GET/query 风格）
     *
     * @param apiSecret API secret
     * @param timestamp 时间戳（毫秒）
     * @param recvWindow recv window（字符串）
     * @param apiKey api key
     * @param params 业务参数（不要包含 api_key、timestamp、recv_window）
     * @return 小写 hex 的签名字符串
     */
    public String generateSignature(String apiSecret, long timestamp, String recvWindow, String apiKey, Map<String, String> params) {
        try {
            String queryString = "";

            if (params != null && !params.isEmpty()) {
                // ✅ 不再排序，按传入顺序拼接
                queryString = params.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining("&"));
            }

            // ✅ 按 Bybit 规则拼接签名原文
            String origin = timestamp + apiKey + recvWindow + queryString;
            log.debug("签名原始 queryString: {}", origin);

            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(origin.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }

            log.debug("生成签名: {}", sb.toString());
            return sb.toString();

        } catch (Exception e) {
            log.error("生成签名失败", e);
            throw new RuntimeException("生成签名失败", e);
        }
    }


    private String buildSortedQueryString(Map<String, String> params) {
        if (params == null || params.isEmpty()) return "";
        // 按 key 升序排序
        return params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
    }

    private String hmacSha256Hex(String data, String secret) {
        try {
            Mac sha256_HMAC = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secret_key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            sha256_HMAC.init(secret_key);
            byte[] hash = sha256_HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMACSHA256 error", e);
        }
    }
    /**
     * 专门为持仓查询生成的签名方法
     */
    public Map<String, String> generatePositionQuerySignature(String apiKey, String apiSecret, String symbol) {
        long timestamp = Instant.now().toEpochMilli();
        String recvWindow = "5000";

        // 构建查询参数（不包括签名本身）
        Map<String, String> params = new HashMap<>();
        params.put("category", "linear");
        params.put("symbol", symbol);

        // 生成签名
        String signature = generateSignature(apiSecret, timestamp, recvWindow, apiKey, params);

        // 构建最终参数（包括签名）
        Map<String, String> finalParams = new HashMap<>(params);
        finalParams.put("api_key", apiKey);
        finalParams.put("timestamp", String.valueOf(timestamp));
        finalParams.put("recv_window", recvWindow);
        finalParams.put("sign", signature);

        return finalParams;
    }

    /**
     * 构建查询字符串
     */
    public String buildQueryString(Map<String, String> params) {
        List<String> paramList = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            paramList.add(entry.getKey() + "=" + entry.getValue());
        }
        return String.join("&", paramList);
    }

    /**
     * 为 POST 请求（body 为 JSON 字符串）生成头部认证信息，返回包含必要字段的 Map
     */
    public Map<String, String> generatePostRequestSignature(String apiKey, String apiSecret, String requestBody) {
        long timestamp = Instant.now().toEpochMilli();
        String recvWindow = "5000";

        String payload = timestamp + apiKey + recvWindow + (requestBody == null ? "" : requestBody);
        String sign = hmacSha256Hex(payload, apiSecret);

        Map<String, String> headers = new HashMap<>();
        headers.put("X-BAPI-API-KEY", apiKey);
        headers.put("X-BAPI-TIMESTAMP", String.valueOf(timestamp));
        headers.put("X-BAPI-RECV-WINDOW", recvWindow);
        headers.put("X-BAPI-SIGN", sign);
        return headers;
    }

    /**
     * 生成POST请求签名
     */
    private String generatePostSignature(String apiSecret, long timestamp, String recvWindow,
                                         String apiKey, String requestBody) {
        try {
            // POST请求签名格式：timestamp + apiKey + recvWindow + requestBody
            StringBuilder signData = new StringBuilder();
            signData.append(timestamp);
            signData.append(apiKey);
            signData.append(recvWindow);
            signData.append(requestBody);

            String data = signData.toString();
            log.debug("🔐 POST签名原始数据: {}", data);

            // 使用HMAC SHA256生成签名
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256_HMAC.init(secret_key);

            byte[] hash = sha256_HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8));

            // 转换为十六进制字符串
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            String signature = hexString.toString();
            log.debug("✅ 生成POST签名: {}", signature);

            return signature;

        } catch (Exception e) {
            log.error("❌ 生成POST签名失败", e);
            throw new RuntimeException("生成POST签名失败", e);
        }
    }
}