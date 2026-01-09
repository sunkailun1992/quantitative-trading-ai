package com.trading.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 🚀 Discord Webhook 推送工具类
 * 用于将系统通知、AI交易结果、报警信息推送到 Discord 频道
 */
@Slf4j
@Component
public class DiscordWebhookService {

    /** 主 Webhook 地址（请替换成你的Webhook） */
    private static final String WEBHOOK_URL =
            "https://discord.com/api/webhooks/1438036304511373312/7p6wHE0MgLo9zPV-KvKZAlTNjPIhkb7Zpp5H6VQE_5WmUmWrSxkfofPuoLWVOrwqFENj";

    /** 子频道（Thread）ID，可为空 */
    private static final String THREAD_ID = "1438034472959213688";

    /**
     * 发送文本消息到 Discord（自动分片 + 支持子频道）
     *
     * @param content 消息内容（支持Markdown）
     */
    public void sendMessage(String content) {
        if (content == null || content.isBlank()) {
            log.warn("⚠️ Discord消息为空，跳过推送");
            return;
        }

        // Discord 每条消息上限 2000 字符
        int maxLength = 1900; // 留点空间给转义
        int start = 0;
        int partIndex = 1;
        while (start < content.length()) {
            int end = Math.min(start + maxLength, content.length());
            String part = content.substring(start, end);
            sendSingleMessage(part, partIndex++);
            start = end;
        }
    }

    /**
     * 实际发送单条消息
     */
    private void sendSingleMessage(String content, int index) {
        try {
            // 拼接子频道URL（若存在）
            String targetUrl = WEBHOOK_URL;
            if (THREAD_ID != null && !THREAD_ID.isBlank()) {
                targetUrl += "?thread_id=" + THREAD_ID;
            }

            URL url = new URL(targetUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");

            // JSON payload
            String jsonPayload = String.format("{\"content\": \"%s\"}", escapeJson(content));

            try (OutputStream os = connection.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == 204) {
                log.info("✅ Discord子频道推送成功 (第{}段)", index);
            } else {
                log.error("❌ Discord推送失败 (第{}段)，状态码: {}", index, responseCode);
            }

        } catch (Exception e) {
            log.error("🚨 Discord消息发送异常 (第{}段): {}", index, e.getMessage(), e);
        }
    }

    /**
     * 转义 JSON 特殊字符
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /**
     * 🧪 测试主函数
     */
    public static void main(String[] args) {
        DiscordWebhookService discord = new DiscordWebhookService();
        String testMessage = """
                ## ✅ Discord 子频道推送测试
                这是一个测试消息，发送到指定 Thread。
                📅 时间：%s
                🔔 内容：测试成功请在 Discord 子频道中查看。
                ━━━━━━━━━━━━━━━━━━━━━━━
                """.formatted(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        discord.sendMessage(testMessage);
    }
}
