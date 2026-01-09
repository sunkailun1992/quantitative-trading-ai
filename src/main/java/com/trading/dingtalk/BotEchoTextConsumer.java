package com.trading.dingtalk;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.chatbot.BotReplier;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.trading.entity.MarketOverviewEntity;
import com.trading.entity.TraderStrategyEntity;
import com.trading.repository.MarketOverviewRepository;
import com.trading.repository.TraderStrategyRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 🤖 BotEchoTextConsumer
 * 钉钉 Stream 模式监听类
 * 支持识别并保存：
 *   - 单条/多条交易员策略
 *   - 单条/多条大行情分析
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotEchoTextConsumer implements OpenDingTalkCallbackListener<ChatbotMessage, Void> {

    private final TraderStrategyRepository traderStrategyRepository;
    private final MarketOverviewRepository marketOverviewRepository;

    @Override
    public Void execute(ChatbotMessage message) {
        try {
            // === 1️⃣ 获取消息文本内容 ===
            String content = message.getText() != null ? message.getText().getContent().trim() : "";
            log.info("📩 收到钉钉消息: {}", content);

            // === 2️⃣ 空内容直接提示 ===
            if (content.isBlank()) {
                reply(message, "⚠️ 收到空消息，请发送 JSON 数据或指令。");
                return null;
            }

            // ============================================================
            // === 3️⃣ 新增：命令判断区（删除当天数据）===
            // ============================================================

            // 🗑️ 大行情分析，删除：小助理
            if (content.startsWith("大行情分析，删除：")) {
                String author = content.replace("大行情分析，删除：", "").trim();    // 提取作者名
                if (author.isEmpty()) {
                    reply(message, "⚠️ 删除命令缺少作者名称。");
                    return null;
                }
                deleteMarketOverviewToday(author, message);                         // 调用删除方法
                return null;                                                        // 结束处理
            }

            // 🗑️ 交易员策略，删除：军长
            if (content.startsWith("交易员策略，删除：")) {
                String trader = content.replace("交易员策略，删除：", "").trim();     // 提取交易员名
                if (trader.isEmpty()) {
                    reply(message, "⚠️ 删除命令缺少交易员名称。");
                    return null;
                }
                deleteTraderStrategyToday(trader, message);                         // 调用删除方法
                return null;                                                        // 结束处理
            }

            // ============================================================
            // === 3️⃣ 判断消息类型（数组 or 对象）===
            // ============================================================
            String trimmed = content.trim(); // 去除首尾空格
            // --- ✅ JSON 数组形式（多条记录）---
            if (trimmed.startsWith("[")) {
                JSONArray array = JSON.parseArray(trimmed);      // 解析为 JSON 数组
                if (array.isEmpty()) {
                    reply(message, "⚠️ JSON 数组为空。");
                    return null;
                }

                // 获取数组第一个对象，用于结构判断
                JSONObject first = array.getJSONObject(0);

                // 含有 traderName 字段 → 属于交易员策略数组
                if (first.containsKey("traderName")) {
                    handleTraderStrategyJSON(trimmed, message);
                }
                // 含有 author + fullAnalysis 字段 → 属于大行情数组
                else if (first.containsKey("author") && first.containsKey("fullAnalysis")) {
                    handleMarketOverviewJSONArray(trimmed, message);
                }
                // 无法识别的数组结构
                else {
                    reply(message, "❌ 未识别的 JSON 数组类型，请确认字段是否正确。");
                }
            }

            // --- ✅ JSON 对象形式（单条记录）---
            else if (trimmed.startsWith("{")) {
                JSONObject json = JSON.parseObject(trimmed);     // 解析 JSON 对象

                // 含有 traderName 字段 → 属于交易员策略
                if (json.containsKey("traderName")) {
                    handleSingleTraderStrategy(json, message);
                }
                // 含有 author + fullAnalysis → 属于大行情分析
                else if (json.containsKey("author") && json.containsKey("fullAnalysis")) {
                    handleMarketOverviewJSON(trimmed, message);
                }
                // 无法识别类型
                else {
                    reply(message, "❌ 未识别的 JSON 对象类型，请确认字段名是否正确。");
                }
            }

            // --- ❌ 其他非 JSON 格式 ---
            else {
                reply(message, "❌ JSON 格式错误，请发送对象 `{}` 或数组 `[]`。");
            }

        } catch (Exception e) {
            // 全局异常处理
            log.error("❌ 处理钉钉消息异常: {}", e.getMessage(), e);
            try {
                reply(message, "❌ 系统异常：" + e.getMessage());
            } catch (IOException ignored) {}
        }
        return null;
    }

    // ============================================================
    // 🧩 多条交易员策略数组处理
    // ============================================================
    private void handleTraderStrategyJSON(String content, ChatbotMessage message) throws IOException {
        try {
            JSONArray array = JSON.parseArray(content);

            List<TraderStrategyEntity> list = array.stream()
                    .map(obj -> {
                        JSONObject json = (JSONObject) obj;
                        return TraderStrategyEntity.builder()
                                .traderName(json.getString("traderName"))
                                .symbol(json.getString("symbol"))
                                .direction(json.getString("direction"))
                                .entryRange(json.getString("entryRange"))
                                .stopLoss(json.getString("stopLoss"))
                                .takeProfit(json.getString("takeProfit"))
                                .style(json.getString("style"))
                                .comment(json.getString("comment"))
                                .createdAt(LocalDateTime.now())   // ✅ 使用系统时间
                                .build();
                    })
                    .toList();

            traderStrategyRepository.saveAll(list);
            log.info("✅ 已保存 {} 条交易员策略。", list.size());
            reply(message, "✅ 成功保存 " + list.size() + " 条交易员策略。");

        } catch (Exception e) {
            log.error("❌ 解析交易员策略 JSON 失败: {}", e.getMessage());
            reply(message, "❌ 交易员策略 JSON 格式错误：" + e.getMessage());
        }
    }

    // ============================================================
    // 🧩 单条交易员策略对象处理
    // ============================================================
    private void handleSingleTraderStrategy(JSONObject json, ChatbotMessage message) throws IOException {
        try {
            // 构建单条交易员策略对象
            TraderStrategyEntity entity = TraderStrategyEntity.builder()
                    .traderName(json.getString("traderName"))
                    .symbol(json.getString("symbol"))
                    .direction(json.getString("direction"))
                    .entryRange(json.getString("entryRange"))
                    .stopLoss(json.getString("stopLoss"))
                    .takeProfit(json.getString("takeProfit"))
                    .style(json.getString("style"))
                    .comment(json.getString("comment"))
                    .createdAt(LocalDateTime.now())   // ✅ 使用系统时间
                    .build();

            // 保存数据库
            traderStrategyRepository.save(entity);
            log.info("✅ 已保存单条交易员策略：{}", entity.getTraderName());
            reply(message, "✅ 已保存交易员策略：" + entity.getTraderName());

        } catch (Exception e) {
            log.error("❌ 保存单条交易员策略失败: {}", e.getMessage());
            reply(message, "❌ 单条交易员策略保存失败：" + e.getMessage());
        }
    }

    // ============================================================
    // 🧩 单条大行情分析对象处理
    // ============================================================
    private void handleMarketOverviewJSON(String content, ChatbotMessage message) throws IOException {
        try {
            JSONObject json = JSON.parseObject(content);
            String author = json.getString("author");
            String fullAnalysis = json.getString("fullAnalysis");

            if (author == null || author.isBlank() || fullAnalysis == null || fullAnalysis.isBlank()) {
                reply(message, "⚠️ 缺少必要字段（author 或 fullAnalysis）。");
                return;
            }

            MarketOverviewEntity entity = MarketOverviewEntity.builder()
                    .author(author)
                    .fullAnalysis(fullAnalysis)
                    .createdAt(LocalDateTime.now()) // ✅ 使用系统时间
                    .build();

            marketOverviewRepository.save(entity);
            log.info("✅ 已保存大行情分析 → 作者={} 时间={}", author, entity.getCreatedAt());
            reply(message, "✅ 已保存大行情分析。作者：" + author);

        } catch (Exception e) {
            log.error("❌ 解析大行情 JSON 失败: {}", e.getMessage());
            reply(message, "❌ 大行情 JSON 格式错误：" + e.getMessage());
        }
    }

    // ============================================================
    // 🧩 多条大行情分析数组处理
    // ============================================================
    private void handleMarketOverviewJSONArray(String content, ChatbotMessage message) throws IOException {
        try {
            JSONArray array = JSON.parseArray(content);

            List<MarketOverviewEntity> list = array.stream()
                    .map(obj -> {
                        JSONObject json = (JSONObject) obj;
                        return MarketOverviewEntity.builder()
                                .author(json.getString("author"))
                                .fullAnalysis(json.getString("fullAnalysis"))
                                .createdAt(LocalDateTime.now()) // ✅ 使用系统时间
                                .build();
                    })
                    .toList();

            marketOverviewRepository.saveAll(list);
            log.info("✅ 已保存 {} 条大行情分析记录。", list.size());
            reply(message, "✅ 已成功保存 " + list.size() + " 条大行情分析。");

        } catch (Exception e) {
            log.error("❌ 解析大行情数组失败: {}", e.getMessage());
            reply(message, "❌ 大行情数组 JSON 格式错误：" + e.getMessage());
        }
    }

    /**
     * 🗑️ 删除当天指定交易员策略记录
     *
     * @param trader 交易员名称（如“军长”）
     * @param message 钉钉消息对象，用于回复
     */
    public void deleteTraderStrategyToday(String trader, ChatbotMessage message) throws IOException {
        try {
            // === 1️⃣ 计算当天时间区间（00:00:00 ~ 23:59:59）===
            LocalDateTime startOfDay = LocalDateTime.now()                 // 当前时间
                    .withHour(0).withMinute(0).withSecond(0).withNano(0);  // 当天开始
            LocalDateTime endOfDay = LocalDateTime.now()                   // 当前时间
                    .withHour(23).withMinute(59).withSecond(59);           // 当天结束

            // === 2️⃣ 调用 Repository 删除方法 ===
            // 返回删除的记录数（JPA 自动生成 DELETE SQL）
            int deletedCount = traderStrategyRepository
                    .deleteByTraderNameAndCreatedAtBetween(trader, startOfDay, endOfDay);

            // === 3️⃣ 打印日志 & 回复机器人 ===
            log.info("🗑️ 已删除 {} 条交易员策略 → 交易员={} 时间范围={} ~ {}",
                    deletedCount, trader, startOfDay, endOfDay);

            if (deletedCount > 0) {
                reply(message, "✅ 已删除 " + deletedCount + " 条【" + trader + "】今日交易员策略记录。");
            } else {
                reply(message, "ℹ️ 未找到【" + trader + "】今日的交易员策略记录。");
            }

        } catch (Exception e) {
            // === 4️⃣ 异常处理 ===
            log.error("❌ 删除交易员策略失败: {}", e.getMessage(), e);
            reply(message, "❌ 删除交易员策略失败：" + e.getMessage());
        }
    }

    /**
     * 🗑️ 删除当天指定作者的大行情分析记录
     *
     * @param author 作者名称（如“小助理”）
     * @param message 钉钉消息对象，用于回复
     */
    public void deleteMarketOverviewToday(String author, ChatbotMessage message) throws IOException {
        try {
            // === 1️⃣ 计算当天时间区间（00:00:00 ~ 23:59:59）===
            LocalDateTime startOfDay = LocalDateTime.now()                 // 当前时间
                    .withHour(0).withMinute(0).withSecond(0).withNano(0);  // 当天开始
            LocalDateTime endOfDay = LocalDateTime.now()                   // 当前时间
                    .withHour(23).withMinute(59).withSecond(59);           // 当天结束

            // === 2️⃣ 调用 Repository 删除方法 ===
            // 返回删除的记录数（JPA 自动生成 DELETE SQL）
            int deletedCount = marketOverviewRepository
                    .deleteByAuthorAndCreatedAtBetween(author, startOfDay, endOfDay);

            // === 3️⃣ 打印日志 & 回复机器人 ===
            log.info("🗑️ 已删除 {} 条大行情分析 → 作者={} 时间范围={} ~ {}",
                    deletedCount, author, startOfDay, endOfDay);

            if (deletedCount > 0) {
                reply(message, "✅ 已删除 " + deletedCount + " 条【" + author + "】今日大行情分析记录。");
            } else {
                reply(message, "ℹ️ 未找到【" + author + "】今日的大行情分析记录。");
            }

        } catch (Exception e) {
            // === 4️⃣ 异常处理 ===
            log.error("❌ 删除大行情分析失败: {}", e.getMessage(), e);
            reply(message, "❌ 删除大行情分析失败：" + e.getMessage());
        }
    }

    // ============================================================
    // 📨 通用钉钉消息回复
    // ============================================================
    private void reply(ChatbotMessage message, String text) throws IOException {
        BotReplier.fromWebhook(message.getSessionWebhook()).replyText(text);
    }
}
