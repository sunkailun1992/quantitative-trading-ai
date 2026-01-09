package com.trading.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.trading.util.DingDingMessageUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 🚨 DingDingErrorAppender
 * 自动捕获 ERROR 级别日志并调用 DingDingMessageUtil 发送钉钉报警
 * ✅ 优化版：支持多行排版、美观换行、统一 Markdown 样式
 */
public class DingDingErrorAppender extends AppenderBase<ILoggingEvent> {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    protected void append(ILoggingEvent event) {
        // 仅捕获 ERROR 级别日志
        if (!"ERROR".equals(event.getLevel().toString())) {
            return;
        }

        try {
            // 🔹 基本信息
            String thread = event.getThreadName();
            String logger = event.getLoggerName();
            String message = event.getFormattedMessage();
            String time = LocalDateTime.now().format(TIME_FORMAT);

            // 🔹 标题（方便钉钉搜索）
            String shortMsg = message.length() > 60 ? message.substring(0, 60) + "..." : message;
            String title = String.format("[ERROR][QuantAI] %s（%s）", shortMsg, thread);

            // 🔹 Markdown 消息体（优化换行与对齐）
            String text = String.format("""
                    ### ⚠️ **系统错误告警**

                    🕒 **时间：** %s  
                    🧵 **线程：** %s  
                    🏷️ **类名：** %s  
                    💬 **摘要：**  
                    > %s  

                    ---
                    📁 **日志文件：** `error.log`  
                    🔎 请尽快排查并修复异常。
                    """,
                    time, thread, logger, message
            );

            // ✅ 发送钉钉Markdown消息
            DingDingMessageUtil.sendMarkdown(title, text);

        } catch (Exception e) {
            System.err.println("❌ DingDingErrorAppender 推送失败：" + e.getMessage());
        }
    }
}
