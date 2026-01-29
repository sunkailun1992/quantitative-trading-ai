package com.trading.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.entity.MarketKline1hEntity;
import com.trading.entity.MarketKlineDailyEntity;
import com.trading.entity.MarketKlineEntity;
import com.trading.entity.MarketKlineWeeklyEntity;
import com.trading.repository.MarketKline1hRepository;
import com.trading.repository.MarketKlineDailyRepository;
import com.trading.repository.MarketKlineRepository;
import com.trading.repository.MarketKlineWeeklyRepository;
import com.trading.service.BybitTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 🕒 MarketKlineSyncTask
 * 定时任务：定期同步 Bybit 的 15 分钟K线数据（最近两天），并存入数据库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketKlineSyncTaskJob {

    private final BybitTradingService bybitTradingService;         // Bybit接口服务（负责拉取K线数据）
    private final MarketKlineRepository marketKlineRepository;     // 分钟K数据库仓库
    private final MarketKline1hRepository marketKline1hRepository; // 小时K数据库仓库
    private final MarketKlineDailyRepository marketKlineDailyRepository; // 日K数据库仓库
    private final MarketKlineWeeklyRepository marketKlineWeeklyRepository; // 周K数据库仓库
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    @Value("${trading.symbol}")
    private String SYMBOL;
    private static final String INTERVAL_D = "D";                   // 日K间隔（Bybit支持"D"或"1440"）
    private static final int DAYS = 30;                           // 同步天数：30天
    private static final String INTERVAL_W = "W";                         // 周K线周期
    private static final int WEEKS = 52;                                // 获取一年的K线（约52周）
    // 日期时间格式：2025-10-31 16:00:00
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    @Value("${trading.symbol}")
    private String symbol;
    /**
     * 定时任务：每小时执行一次，从 Bybit 拉取最近两天的15分钟K线，写入数据库并刷新技术指标。
     */
    @Scheduled(cron = "0 1,16,31,46 * * * ?")
    public void syncRecentKlines() {
        try {
            // 获取当前时间字符串
            String currentTime = LocalDateTime.now().format(TIME_FORMATTER);

            // 打印启动日志，包含时间戳
            log.info("🕒 [{}] [定时任务启动] 同步最近2天的15分钟K线数据...", currentTime);

            // === Step 1️⃣ 定义参数 ===
            final int intervalMin = 15;        // 15分钟周期
            final int limit = (24 * 60 / intervalMin); // 1天共96根K线

            // === Step 2️⃣ 调用 Bybit API 获取K线数据 ===
            JsonNode response = bybitTradingService.getKline(symbol, String.valueOf(intervalMin), limit);

            // === Step 3️⃣ 验证响应结构 ===
            if (response == null || !response.has("result") || !response.get("result").has("list")) {
                log.error("❌ Bybit返回数据无效: {}", response);
                return;
            }

            // === Step 4️⃣ 解析K线数组 ===
            JsonNode list = response.get("result").get("list");
            List<JsonNode> klines = new ArrayList<>();
            list.forEach(klines::add);

            // === Step 5️⃣ Bybit返回默认是倒序（最新在前） → 按时间升序排列 ===
            klines.sort(Comparator.comparingLong(k -> k.get(0).asLong()));


            // === Step 移除最后一根“未闭合K线”以防污染指标 ===
            if (!klines.isEmpty()) {
                JsonNode last = klines.remove(klines.size() - 1);
                log.info("⚠️ 已移除未闭合K线: 时间={} 收盘价={}",
                        last.get(0).asText(), last.get(4).asText());
            }

            // === Step 7️⃣ 批量写入数据库 ===
            int insertedCount = 0;
            for (JsonNode k : klines) {
                long openTimeMs = k.get(0).asLong();                 // Bybit时间戳（毫秒）
                LocalDateTime openTime = Instant.ofEpochMilli(openTimeMs)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime();                          // ✅ 转为LocalDateTime存数据库

                // 检查是否重复（防止重复插入）
                if (marketKlineRepository.existsBySymbolAndOpenTime(symbol, openTime)) {
                    continue; // 已存在则跳过
                }

                // 创建并填充实体对象
                MarketKlineEntity entity = new MarketKlineEntity();
                entity.setSymbol(symbol);
                entity.setIntervalMin(intervalMin);
                entity.setOpenTime(openTime);
                entity.setOpen(k.get(1).asDouble());
                entity.setHigh(k.get(2).asDouble());
                entity.setLow(k.get(3).asDouble());
                entity.setClose(k.get(4).asDouble());
                entity.setVolume(k.get(5).asDouble());
                entity.setCreatedAt(LocalDateTime.now());

                // 写入数据库
                marketKlineRepository.save(entity);
                insertedCount++;
            }
            // === Step 7️⃣ 打印完成日志 ===
            log.info("✅ [任务完成] 同步15分钟K线成功 - 新增记录: {} 条 | 时间范围: 最近 {} 分钟 | 执行时间: {}",
                    insertedCount, limit, currentTime);
        } catch (Exception e) {
            log.error("❌ 同步15分钟K线任务执行失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 🕑 每小时第2分钟执行，抓取最近2天（48根）1小时K线
     */
    @Scheduled(cron = "0 2 * * * ?")
    public void syncHourlyKlines() {
        String now = LocalDateTime.now().format(TIME_FMT);
        log.info("🕑 [{}] [定时任务启动] 同步最近2天的1小时K线数据...", now);

        try {
            // 获取最近48根 1小时K线（Bybit返回新->旧）
            JsonNode response = bybitTradingService.getKline(SYMBOL, "60", 48);

            if (response == null || !response.has("result") || !response.get("result").has("list")) {
                log.warn("⚠️ [{}] 1小时K线响应无效或为空", now);
                return;
            }

            JsonNode list = response.get("result").get("list");
            List<JsonNode> klines = new ArrayList<>();
            list.forEach(klines::add);

            // 按时间升序排列
            klines.sort(Comparator.comparingLong(k -> k.get(0).asLong()));

            // === Step 移除最后一根“未闭合K线”以防污染指标 ===
            if (!klines.isEmpty()) {
                JsonNode last = klines.remove(klines.size() - 1);
                log.info("⚠️ 已移除未闭合K线: 时间={} 收盘价={}",
                        last.get(0).asText(), last.get(4).asText());
            }
            int inserted = 0;
            for (JsonNode kline : klines) {
                long openTimeMillis = kline.get(0).asLong();
                LocalDateTime openTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(openTimeMillis), ZoneId.systemDefault());

                if (marketKline1hRepository.existsBySymbolAndOpenTime(SYMBOL, openTime)) {
                    continue; // 已存在则跳过
                }

                MarketKline1hEntity entity = new MarketKline1hEntity();
                entity.setSymbol(SYMBOL);
                entity.setOpenTime(openTime);
                entity.setOpen(kline.get(1).asDouble());
                entity.setHigh(kline.get(2).asDouble());
                entity.setLow(kline.get(3).asDouble());
                entity.setClose(kline.get(4).asDouble());
                entity.setVolume(kline.get(5).asDouble());

                marketKline1hRepository.save(entity);
                inserted++;
            }
            // === Step 7️⃣ 打印完成日志 ===
            log.info("✅ [任务完成] 同步1小时K线成功 - 新增记录: {} 条 | 时间范围: 最近 {} 小时 | 执行时间: {}",
                    inserted, 48, now);

        } catch (Exception e) {
            log.error("❌ [{}] 同步1小时K线数据失败: {}", now, e.getMessage(), e);
        }
    }

    /**
     * 定时任务：每天 08:30 执行一次
     * 抓取近30天日K数据，写入数据库（去重）。
     */
    @Scheduled(cron = "0 30 8 * * ?")
    public void syncDailyKlines30d() {
        try {
            // === Step 1️⃣ 获取当前执行时间 ===
            String currentTime = LocalDateTime.now().format(TIME_FORMATTER);
            log.info("🕒 [{}] [定时任务启动] 同步最近 {} 天的日K线数据 (1D)...", currentTime, DAYS);

            // === Step 2️⃣ 调用Bybit API获取K线数据 ===
            JsonNode response = bybitTradingService.getKline(SYMBOL, INTERVAL_D, DAYS + 2); // 多取两根防止未闭合
            if (response == null || !response.has("result") || !response.get("result").has("list")) {
                log.error("❌ Bybit返回数据无效或为空: {}", response);
                return;
            }

            // === Step 3️⃣ 解析K线数组 ===
            JsonNode klineList = response.get("result").get("list");
            List<JsonNode> klines = new ArrayList<>();
            for (JsonNode k : klineList) {
                klines.add(k);
            }

            // === Step 4️⃣ 按时间升序排列（Bybit返回默认新→旧）===
            klines.sort(Comparator.comparingLong(k -> k.get(0).asLong()));

            // === Step 5️⃣ 移除最后一根未闭合K线（防止错误计算）===
            if (!klines.isEmpty()) {
                JsonNode last = klines.remove(klines.size() - 1);
                log.info("⚠️ 已移除未闭合K线: 时间={} 收盘价={}",
                        last.get(0).asText(), last.get(4).asText());
            }

            // === Step 6️⃣ 批量写入数据库（带去重）===
            int insertedCount = 0;
            for (JsonNode k : klines) {
                long openTimeMs = k.get(0).asLong(); // 时间戳（毫秒）
                LocalDateTime openTime = Instant.ofEpochMilli(openTimeMs)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime(); // 转换为本地时间存数据库

                // 检查重复
                if (marketKlineDailyRepository.existsBySymbolAndOpenTime(SYMBOL, openTime)) {
                    continue; // 已存在则跳过
                }

                // 创建并填充实体
                MarketKlineDailyEntity entity = new MarketKlineDailyEntity();
                entity.setSymbol(SYMBOL);
                entity.setIntervalMin(1440); // 日K = 1440分钟
                entity.setOpenTime(openTime);
                entity.setOpen(k.get(1).asDouble());
                entity.setHigh(k.get(2).asDouble());
                entity.setLow(k.get(3).asDouble());
                entity.setClose(k.get(4).asDouble());
                entity.setVolume(k.get(5).asDouble());
                entity.setCreatedAt(LocalDateTime.now());

                // 保存
                marketKlineDailyRepository.save(entity);
                insertedCount++;
            }

            // === Step 7️⃣ 打印结果日志 ===
            log.info("✅ [任务完成] 同步日K线成功 - 新增记录: {} 条 | 窗口: {} 天 | 执行时间: {}",
                    insertedCount, DAYS, currentTime);

        } catch (Exception e) {
            log.error("❌ 同步日K线任务执行失败: {}", e.getMessage(), e);
        }
    }

    /**
     * ⏰ 每周一 9:00 执行一次
     * 抓取过去52周的周K数据，并存入数据库。
     */
    @Scheduled(cron = "0 0 9 ? * MON")
    public void syncWeeklyKlines1Y() {
        try {
            // === Step 1️⃣ 获取当前执行时间 ===
            String currentTime = LocalDateTime.now().format(TIME_FORMATTER);
            log.info("🕒 [{}] [定时任务启动] 同步最近 {} 周的周K线数据 (1W)...", currentTime, WEEKS);

            // === Step 2️⃣ 调用Bybit API获取K线数据 ===
            JsonNode response = bybitTradingService.getKline(SYMBOL, INTERVAL_W, WEEKS + 2);
            if (response == null || !response.has("result") || !response.get("result").has("list")) {
                log.error("❌ Bybit返回数据无效或为空: {}", response);
                return;
            }

            // === Step 3️⃣ 解析K线数据 ===
            JsonNode klineList = response.get("result").get("list");
            List<JsonNode> klines = new ArrayList<>();
            for (JsonNode k : klineList) klines.add(k);

            // === Step 4️⃣ 按时间升序排列 ===
            klines.sort(Comparator.comparingLong(k -> k.get(0).asLong()));

            // === Step 5️⃣ 移除最后一根未闭合K线 ===
            if (!klines.isEmpty()) {
                JsonNode last = klines.remove(klines.size() - 1);
                log.info("⚠️ 已移除未闭合周K: 时间={} 收盘价={}", last.get(0).asText(), last.get(4).asText());
            }

            // === Step 6️⃣ 批量插入数据库 ===
            int insertedCount = 0;
            for (JsonNode k : klines) {
                long openTimeMs = k.get(0).asLong(); // 时间戳（毫秒）
                LocalDateTime openTime = Instant.ofEpochMilli(openTimeMs)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime();

                // 检查重复
                if (marketKlineWeeklyRepository.existsBySymbolAndOpenTime(SYMBOL, openTime)) {
                    continue;
                }

                // 组装实体
                MarketKlineWeeklyEntity entity = new MarketKlineWeeklyEntity();
                entity.setSymbol(SYMBOL);
                entity.setIntervalMin(10080); // 周K=7*24*60分钟
                entity.setOpenTime(openTime);
                entity.setOpen(k.get(1).asDouble());
                entity.setHigh(k.get(2).asDouble());
                entity.setLow(k.get(3).asDouble());
                entity.setClose(k.get(4).asDouble());
                entity.setVolume(k.get(5).asDouble());
                entity.setCreatedAt(LocalDateTime.now());

                // 写入数据库
                marketKlineWeeklyRepository.save(entity);
                insertedCount++;
            }

            // === Step 7️⃣ 打印完成日志 ===
            log.info("✅ [任务完成] 同步周K线成功 - 新增记录: {} 条 | 时间范围: 最近 {} 周 | 执行时间: {}",
                    insertedCount, WEEKS, currentTime);

        } catch (Exception e) {
            log.error("❌ 同步周K线任务执行失败: {}", e.getMessage(), e);
        }
    }
}
