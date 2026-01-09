package com.trading.util;

import com.alibaba.fastjson2.JSONObject;  // ✅ FastJSON 2
import com.trading.aliyun.DingDing;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 📨 DingDingMessageUtil
 * 钉钉消息发送工具类（FastJSON 2 版本）
 */
@Slf4j
@Component
public class DingDingMessageUtil {

    public static void sendText(String content) {
        try {
            JSONObject json = new JSONObject();
            json.put("msgtype", "text");

            JSONObject text = new JSONObject();
            text.put("content", content);
            json.put("text", text);

            doSend(json);
        } catch (Exception e) {
            log.error("❌ 发送钉钉文本消息失败: {}", e.getMessage());
        }
    }

    public static void sendMarkdown(String title, String text) {
        try {
            JSONObject json = new JSONObject();
            json.put("msgtype", "markdown");

            JSONObject markdown = new JSONObject();
            markdown.put("title", title);
            markdown.put("text", text);
            json.put("markdown", markdown);

            doSend(json);
        } catch (Exception e) {
            log.error("❌ 发送钉钉Markdown消息失败: {}", e.getMessage());
        }
    }

    public static void sendError(String title, String message, Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append("❗ **系统异常报警**\n");
        sb.append("> **标题:** ").append(title).append("\n");
        sb.append("> **消息:** ").append(message).append("\n");
        if (e != null) {
            sb.append("> **异常:** ").append(e.getClass().getSimpleName())
                    .append(" - ").append(e.getMessage()).append("\n");
        }
        sendMarkdown("⚠️ 系统异常", sb.toString());
    }

    private static void doSend(JSONObject body) {
        try {
            String baseUrl = DingDing.url;
            String secret = DingDing.secret;

            if (baseUrl == null || secret == null) {
                log.error("❌ 钉钉配置未加载，请检查 application.yml 中的 aliyun.dingding 配置");
                return;
            }

            long timestamp = System.currentTimeMillis();
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), "UTF-8");

            String requestUrl = baseUrl + "&timestamp=" + timestamp + "&sign=" + sign;

            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>(body.toJSONString(), headers);
            ResponseEntity<String> response = restTemplate.postForEntity(requestUrl, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                log.info("✅ 钉钉消息发送成功: {}", body.toJSONString());
            } else {
                log.warn("⚠️ 钉钉消息发送失败: {}", response.getBody());
            }
        } catch (Exception e) {
            log.error("❌ 发送钉钉消息异常: {}", e.getMessage());
        }
    }

    public static void main(String[] args) {
        // ✅ Step 1：先手动设置钉钉配置（若不在Spring环境中）
        DingDing.url = "https://oapi.dingtalk.com/robot/send?access_token=1389c99315f5904a936f743064f600703a6a7489d65db1014534d9b175192c25"; // ⚠️ 替换成你的真实Webhook
        DingDing.secret = "SEC281caab76eb6a0c6c3db091464fa0e3741b1034cd208eb49e8c4ddc23dfbafb4"; // ⚠️ 替换成你的真实secret

        // ✅ Step 2：构建测试消息内容
        String title = "🚀 DingTalk 消息发送测试";
        String text = """
                ### ✅ 测试钉钉机器人消息发送
                > 发送时间：%s  
                > 测试内容：这是一条来自 **Java程序** 的消息  
                > 状态：正常 ✅
                """.formatted(java.time.LocalDateTime.now());

        // ✅ Step 3：发送Markdown格式消息
        DingDingMessageUtil.sendMarkdown(title, text);

        // ✅ Step 4：再发一条纯文本消息测试
        DingDingMessageUtil.sendText("🔔 测试成功：钉钉机器人发送正常！");

        // ✅ Step 5：模拟一个异常通知
        try {
            int x = 1 / 0;
        } catch (Exception e) {
            DingDingMessageUtil.sendError("异常捕获测试", "测试触发异常通知", e);
        }
    }
}
