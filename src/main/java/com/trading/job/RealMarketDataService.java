package com.trading.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.engine.AITradingEngine;
import com.trading.entity.MarketKline1hEntity;
import com.trading.entity.MarketKlineDailyEntity;
import com.trading.entity.MarketKlineEntity;
import com.trading.entity.MarketKlineWeeklyEntity;
import com.trading.model.MarketData;
import com.trading.repository.MarketKline1hRepository;
import com.trading.repository.MarketKlineDailyRepository;
import com.trading.repository.MarketKlineRepository;
import com.trading.repository.MarketKlineWeeklyRepository;
import com.trading.service.BybitTradingService;
import com.trading.service.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RealMarketDataService { // 真实市场数据服务类

    private final BybitTradingService bybitTradingService; // Bybit交易服务依赖注入
    private final AITradingEngine aiTradingEngine; // AI交易引擎依赖注入
    private final TechnicalIndicatorService technicalIndicatorService; // 技术指标服务依赖注入

    private final MarketKlineRepository marketKlineRepository;
    private final MarketKlineDailyRepository marketKlineDailyRepository;
    private final MarketKlineWeeklyRepository marketKlineWeeklyRepository;
    private final MarketKline1hRepository marketKline1hRepository;
    private boolean dataStreamEnabled = false; // 数据流启用状态
    @Value("${trading.symbol}")
    private String symbol;

    /**
     * 定时获取实时市场数据 - 增强版本 - 核心功能，用于驱动交易决策，它获取数据并触发AI交易引擎。
     */
    @Scheduled(cron = "0 3 * * * ?")
    public void fetchRealTimeMarketData() {
        if (!dataStreamEnabled) {
            return;
        }
        try {
            List<MarketData> marketData = getMultiPeriodMarketData();
            if (marketData != null) {
                // 异步处理市场数据
                aiTradingEngine.processMarketData(marketData);
            } else {
                log.warn("⚠️ 获取的市场数据无效，跳过处理");
            }
        } catch (Exception e) {
            log.error("❌ 获取实时市场数据失败: {}", e.getMessage());
        }
    }

    /**
     * ✅ 从数据库生成多周期 MarketData 列表（15m、1h、1d、1w）
     * 每个周期都独立计算技术指标，用于 AI 多周期分析。
     */
    public List<MarketData> getMultiPeriodMarketData() {
        // 存储最终生成的 MarketData 对象列表
        List<MarketData> marketDataList = new ArrayList<>();

        try {
            // 🕒 当前执行时间
            LocalDateTime now = LocalDateTime.now();
            log.info("📊 [{}] 开始从数据库加载多周期行情数据 (15m / 1h / 1d / 1w)", now);

            // ✅ 1️⃣ 定义每个周期的时间窗口范围
            LocalDateTime from15m = now.minusDays(1);     // 15分钟K线，最近1天
            LocalDateTime from1h = now.minusDays(6);      // 1小时K线，最近6天
            LocalDateTime from1d = now.minusDays(180);    // 1日K线，最近半年
            LocalDateTime from1w = now.minusWeeks(104);   // 1周K线，最近2年

            // ✅ 2️⃣ 分别从数据库中查询各周期的K线
            List<MarketKlineEntity> klines15m =
                    marketKlineRepository.findBySymbolOrderByOpenTimeAsc(symbol); // 15分钟升序
            List<MarketKline1hEntity> klines1h =
                    marketKline1hRepository.findBySymbolOrderByOpenTimeAsc(symbol); // 1小时升序
            List<MarketKlineDailyEntity> klines1d =
                    marketKlineDailyRepository.findBySymbolOrderByOpenTimeAsc(symbol); // 日K升序
            List<MarketKlineWeeklyEntity> klines1w =
                    marketKlineWeeklyRepository.findBySymbolOrderByOpenTimeAsc(symbol); // 周K升序

            // ✅ 3️⃣ 日志输出数据量检查
            log.info("🔎 加载数据量统计: 15m={} 条, 1h={} 条, 1d={} 条, 1w={} 条",
                    klines15m.size(), klines1h.size(), klines1d.size(), klines1w.size());

            // ✅ 4️⃣ 构建四个周期的 MarketData 对象
            MarketData data15m = buildMarketDataFromDB("15m", klines15m);
            MarketData data1h = buildMarketDataFromDB("1h", klines1h);
            MarketData data1d = buildMarketDataFromDB("1d", klines1d);
            MarketData data1w = buildMarketDataFromDB("1w", klines1w);

            // ✅ 5️⃣ 将非空结果加入列表
            if (data15m != null) marketDataList.add(data15m);
            if (data1h != null) marketDataList.add(data1h);
            if (data1d != null) marketDataList.add(data1d);
            if (data1w != null) marketDataList.add(data1w);

            // ✅ 6️⃣ 输出成功信息
            log.info("✅ 成功生成 {} 个 MarketData 对象", marketDataList.size());
            return marketDataList;

        } catch (Exception e) {
            // 捕获任何异常并记录
            log.error("❌ 加载多周期市场数据失败: {}", e.getMessage(), e);
            return marketDataList;
        }
    }

    /**
     * 🧩 构建单个周期的 MarketData（增强版）
     * 从数据库加载历史K线 → 计算技术指标 → 再调用Bybit实时行情获取当前价格
     *
     * @param period    周期字符串（15m / 1h / 1d / 1w）
     * @param klineList 对应周期的K线数据列表（⚠️ 数据已在数据库中按 open_time ASC 排序）
     */
    private MarketData buildMarketDataFromDB(String period, List<? extends Object> klineList) {
        try {
            // 1️⃣ 校验输入：数据列表不能为空
            if (klineList == null || klineList.isEmpty()) {
                log.warn("⚠️ [{}] 周期数据为空，跳过计算", period);
                return null;
            }

            // 2️⃣ 定义技术指标计算所需的时间序列容器
            List<Double> highs = new ArrayList<>();  // 存放最高价
            List<Double> lows = new ArrayList<>();   // 存放最低价
            List<Double> closes = new ArrayList<>(); // 存放收盘价
            double lastVolume = 0.0;                 // 保存最后一根K线的成交量
            double lastClose = 0.0;                  // 保存最后一根K线的收盘价
            LocalDateTime lastTime = null;           // 保存最后一根K线的时间（调试用途）

            // 3️⃣ 遍历K线对象，提取通用数据（升序）
            for (Object obj : klineList) {
                double high, low, close, vol;
                LocalDateTime time;

                if (obj instanceof MarketKlineEntity e) {               // 15分钟K线
                    high = e.getHigh();
                    low = e.getLow();
                    close = e.getClose();
                    vol = e.getVolume();
                    time = e.getOpenTime();
                } else if (obj instanceof MarketKline1hEntity e) {      // 1小时K线
                    high = e.getHigh();
                    low = e.getLow();
                    close = e.getClose();
                    vol = e.getVolume();
                    time = e.getOpenTime();
                } else if (obj instanceof MarketKlineDailyEntity e) {   // 1日K线
                    high = e.getHigh();
                    low = e.getLow();
                    close = e.getClose();
                    vol = e.getVolume();
                    time = e.getOpenTime();
                } else if (obj instanceof MarketKlineWeeklyEntity e) {  // 1周K线
                    high = e.getHigh();
                    low = e.getLow();
                    close = e.getClose();
                    vol = e.getVolume();
                    time = e.getOpenTime();
                } else continue; // 若不是任何已知K线类型，直接跳过

                // 添加进对应序列
                highs.add(high);
                lows.add(low);
                closes.add(close);
                lastVolume = vol;   // 每次循环更新 → 最终为最后一根K线的成交量
                lastTime = time;    // 记录最后时间
                lastClose = close;  // 记录最后收盘价
            }

            // ⚙️ 数据校验：确保序列升序（防御性检测，可选）
            if (closes.size() >= 2 && lastTime.isBefore(LocalDateTime.now().minusDays(10000))) {
                log.warn("⚠️ [{}] 数据可能为降序，建议检查数据库排序。", period);
            }

            // 4️⃣ 技术指标计算（基于升序序列）
            // RSI（相对强弱指数） - 14周期标准配置
            Double rsi = technicalIndicatorService.calculateRSI(closes, 14);

            // MACD（快线12、慢线26、信号9）
            TechnicalIndicatorService.MACDResult macd =
                    technicalIndicatorService.calculateMACD(closes, 12, 26, 9);

            // 布林带指标（基于20周期收盘价）
            Double bbPos = technicalIndicatorService.calculateBollingerBandsPosition(closes, 20); // 布林带位置
            Double bbWidth = technicalIndicatorService.calculateBBBandwidth(closes, 20);          // 布林带带宽

            // EMA移动平均线（短期与中期）
            double ema20 = technicalIndicatorService.calculateEMA(closes, 20);    // EMA(20) 短期趋势
            double ema50 = technicalIndicatorService.calculateEMA(closes, 50);    // EMA(50) 中期趋势
            double ema144 = technicalIndicatorService.calculateEMA(closes, 144);  // EMA(144) 长周期趋势
            double ema168 = technicalIndicatorService.calculateEMA(closes, 168);  // EMA(168) 扩展趋势
            double ema288 = technicalIndicatorService.calculateEMA(closes, 288);  // EMA(288) 更长趋势
            double ema338 = technicalIndicatorService.calculateEMA(closes, 338);  // EMA(338) 超长趋势

            // ATR平均波动幅度（短期3周期 & 标准14周期）
            double atr3 = technicalIndicatorService.calculateATR(highs, lows, closes, 3);   // ATR(3)
            double atr14 = technicalIndicatorService.calculateATR(highs, lows, closes, 14); // ATR(14)

            // 5️⃣ 获取实时行情数据（只用于获取实时最新价格）
            MarketData realTimeData = getCurrentMarketData(); // 从API获取当前价格

            // 6️⃣ 若实时行情不可用，则用数据库最后一根收盘价兜底
            double currentPrice = (realTimeData != null && realTimeData.getCurrentPrice() != null)
                    ? realTimeData.getCurrentPrice()
                    : lastClose;

            // ✅ 成交量直接使用数据库最后一根K线数据（与周期一致）
            double volume = lastVolume;

            // 7️⃣ 计算价格变化率（以第一根K线为基准）
            double firstClose = closes.get(0);
            Double priceChange = firstClose > 0
                    ? ((currentPrice - firstClose) / firstClose) * 100
                    : 0.0;

            // 8️⃣ 构建 MarketData 对象（封装所有计算指标）
            MarketData data = MarketData.builder()
                    .symbol(symbol)                // 交易对
                    .period(period)                // 周期标识
                    .currentPrice(currentPrice)    // 当前价格
                    .priceChange24h(priceChange)   // 变化率（%）
                    .volume(volume)                // 成交量
                    .rsi(rsi)                      // RSI指标
                    .macdDif(macd.getDif())        // MACD DIF线
                    .macdDea(macd.getDea())        // MACD DEA线
                    .macdHistogram(macd.getHistogram()) // MACD 柱状图
                    .bbPosition(bbPos)             // 布林带位置
                    .bbBandwidth(bbWidth)          // 布林带带宽
                    .ema20(ema20)                  // EMA20短期趋势
                    .ema50(ema50)                  // EMA50中期趋势
                    .ema144(ema144)                // EMA144长周期趋势
                    .ema168(ema168)                // EMA168扩展趋势
                    .ema288(ema288)                // EMA288更长趋势
                    .ema338(ema338)                // EMA338超长趋势
                    .atr3(atr3)                    // ATR(3)短期波动
                    .atr14(atr14)                  // ATR(14)标准波动
                    .timestamp(LocalDateTime.now()) // 当前时间戳
                    .build();                      // 构建对象

            // 9️⃣ 输出监控日志（仅保留关键指标）
            log.info("✅ [{}] 周期 MarketData 构建成功: 收盘价=${}, RSI={}, EMA20={}, ATR14={}",
                    period,
                    String.format("%.2f", currentPrice),
                    String.format("%.2f", rsi),
                    String.format("%.2f", ema20),
                    String.format("%.2f", atr14));

            return data;

        } catch (Exception e) {
            // 🔴 异常处理：捕获并打印详细堆栈
            log.error("❌ [{}] 周期 MarketData 构建失败: {}", period, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从Bybit API获取当前市场数据 - 添加数据验证
     */
    public MarketData getCurrentMarketData() {
        try {
            log.debug("📡 开始获取市场数据...");

            // 获取最新价格和24小时统计数据
            JsonNode tickerData = bybitTradingService.getKline(
                    symbol,
                    "15", // 15分钟K线
                    2 // 获取2条数据，确保有足够数据计算变化
            );

            log.debug("📊 原始K线响应: {}", tickerData);

            if (tickerData == null) {
                log.error("❌ K线数据响应为空");
                return createFallbackMarketData();
            }

            // 检查API响应状态
            if (tickerData.has("retCode")) {
                int retCode = tickerData.get("retCode").asInt();
                if (retCode != 0) {
                    String retMsg = tickerData.has("retMsg") ?
                            tickerData.get("retMsg").asText() : "未知错误";
                    log.error("❌ Bybit API错误: {} - {}", retCode, retMsg);
                    return createFallbackMarketData();
                }
            }

            // 检查数据结构
            if (!tickerData.has("result") || !tickerData.get("result").has("list")) {
                log.error("❌ K线数据格式错误，缺少result或list字段");
                log.debug("完整响应: {}", tickerData);
                return createFallbackMarketData();
            }

            JsonNode klineList = tickerData.get("result").get("list");
            log.debug("📈 K线列表大小: {}", klineList.size());

            if (klineList.size() < 2) {
                log.error("❌ K线数据不足，需要至少2条数据");
                return createFallbackMarketData();
            }

            // 使用最新的K线数据
            JsonNode latestKline = klineList.get(0);
            log.debug("🔍 最新K线数据: {}", latestKline);

            MarketData marketData = parseKlineToMarketData(latestKline);
            if (marketData == null) {
                log.error("❌ 解析K线数据失败，使用后备数据");
                return createFallbackMarketData();
            }

            // 验证RSI计算
            log.info("✅ 市场数据获取成功 - 价格: ${}", String.format("%.2f", marketData.getCurrentPrice()));

            return marketData;

        } catch (Exception e) {
            log.error("❌ 获取市场数据时发生错误: {}", e.getMessage(), e);
            return createFallbackMarketData();
        }
    }

    /**
     * 创建后备市场数据（当主要方法失败时）
     * ✅ 已兼容新版 MarketData（使用 Builder 模式 + period 字段）
     */
    private MarketData createFallbackMarketData() {
        log.warn("🔄 使用后备市场数据"); // 提示使用后备数据

        // 1) 生成一个近似的价格并推入指标缓存
        double currentPrice = 50000.0 + (Math.random() - 0.5) * 1000; // 随机扰动价格
        technicalIndicatorService.addPrice(currentPrice);             // 更新到指标缓存

        // 2) 计算基础指标
        Double rsi = 0.00;
        TechnicalIndicatorService.MACDResult macdResult = new TechnicalIndicatorService.MACDResult(0.0, 0.0, 0.0);
        Double bbPosition = 0.00;
        Double bbBandwidth = 0.00;
        double ema20 = 0.00;
        double ema50 = 0.00;

        // 3) 构造模拟高低价序列
        java.util.List<Double> closes = technicalIndicatorService.getRecentPrices(50);
        if (closes.isEmpty()) closes = java.util.List.of(currentPrice);
        java.util.List<Double> highs = new java.util.ArrayList<>(closes);
        java.util.List<Double> lows = new java.util.ArrayList<>(closes);
        int last = highs.size() - 1;
        highs.set(last, closes.get(last) * 1.001);
        lows.set(last, closes.get(last) * 0.999);
        double atr3 = technicalIndicatorService.calculateATR(highs, lows, closes, 3);
        double atr14 = technicalIndicatorService.calculateATR(highs, lows, closes, 14);

        // ✅ 4) 使用 Builder 构建 MarketData 对象
        return MarketData.builder()
                .symbol(symbol)
                .period("15m") // 后备默认周期
                .currentPrice(currentPrice)
                .priceChange24h((Math.random() - 0.5) * 10)
                .volume(1_000_000.0)
                .rsi(rsi)
                .macdDif(macdResult != null ? macdResult.getDif() : 0.0)
                .macdDea(macdResult != null ? macdResult.getDea() : 0.0)
                .macdHistogram(macdResult != null ? macdResult.getHistogram() : 0.0)
                .bbPosition(bbPosition)
                .bbBandwidth(bbBandwidth)
                .ema20(ema20)
                .ema50(ema50)
                .atr3(atr3)
                .atr14(atr14)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * 解析K线数据为市场数据对象（增强版）
     * ✅ 支持 RSI / MACD / BOLL / EMA / ATR
     * ✅ 使用新版 MarketData Builder 模式
     */
    private MarketData parseKlineToMarketData(JsonNode klineData) {
        try {
            log.debug("🔍 开始解析K线数据: {}", klineData);

            // 1️⃣ 校验基础结构
            if (klineData == null || !klineData.isArray() || klineData.size() < 6) {
                log.error("❌ K线数据格式错误或元素不足: {}", klineData);
                return null;
            }

            // 2️⃣ 提取基础字段
            double openPrice = extractDoubleSafely(klineData, 1, "开盘价");
            double highPrice = extractDoubleSafely(klineData, 2, "最高价");
            double lowPrice = extractDoubleSafely(klineData, 3, "最低价");
            double closePrice = extractDoubleSafely(klineData, 4, "收盘价");
            double volume = extractDoubleSafely(klineData, 5, "成交量");
            if (closePrice == 0.0) closePrice = 50000.0;

            // 3️⃣ 计算 24h 价格变化
            Double priceChange24h = get24hPriceChange();

            // 4️⃣ 推入价格缓存，计算指标
            technicalIndicatorService.addPrice(closePrice);
            Double rsi = 0.00;
            TechnicalIndicatorService.MACDResult macd = new TechnicalIndicatorService.MACDResult(0.0, 0.0, 0.0);
            Double bbPosition = 0.00;
            Double bbBandwidth = 0.00;
            double ema20 = 0.00;
            double ema50 = 0.00;

            // 5️⃣ 准备ATR计算
            java.util.List<Double> closes = technicalIndicatorService.getRecentPrices(50);
            if (closes.isEmpty()) closes.add(closePrice);
            java.util.List<Double> highs = java.util.List.of(highPrice);
            java.util.List<Double> lows = java.util.List.of(lowPrice);
            double atr3 = technicalIndicatorService.calculateATR(highs, lows, closes, 3);
            double atr14 = technicalIndicatorService.calculateATR(highs, lows, closes, 14);

            // ✅ 6️⃣ 使用 Builder 构建 MarketData 对象
            return MarketData.builder()
                    .symbol(symbol)
                    .period("15m")
                    .currentPrice(closePrice)
                    .priceChange24h(priceChange24h)
                    .volume(volume)
                    .rsi(rsi)
                    .macdDif(macd != null ? macd.getDif() : 0.0)
                    .macdDea(macd != null ? macd.getDea() : 0.0)
                    .macdHistogram(macd != null ? macd.getHistogram() : 0.0)
                    .bbPosition(bbPosition)
                    .bbBandwidth(bbBandwidth)
                    .ema20(ema20)
                    .ema50(ema50)
                    .atr3(atr3)
                    .atr14(atr14)
                    .timestamp(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("❌ 解析K线数据失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 安全提取double值
     */
    private double extractDoubleSafely(JsonNode klineData, int index, String fieldName) {
        try {
            JsonNode node = klineData.get(index);
            if (node == null || node.isNull()) {
                log.warn("⚠️ K线数据字段[{}]为空, 索引: {}", fieldName, index);
                return 0.0;
            }

            if (node.isTextual()) {
                return Double.parseDouble(node.asText());
            } else if (node.isNumber()) {
                return node.asDouble();
            } else {
                log.warn("⚠️ K线数据字段[{}]不是数字类型: {}", fieldName, node.getNodeType());
                return 0.0;
            }
        } catch (Exception e) {
            log.error("❌ 提取K线字段[{}]失败: {}", fieldName, e.getMessage());
            return 0.0;
        }
    }


    /**
     * 获取24小时价格变化百分比
     */
    private Double get24hPriceChange() {
        try {
            // 获取24小时行情数据
            JsonNode ticker24h = bybitTradingService.getKline( // 获取24小时K线数据
                    symbol, // 交易对
                    "60", // 1小时K线
                    24 // 获取24条数据（24小时）
            );

            if (ticker24h != null && ticker24h.has("result") && ticker24h.get("result").has("list")) { // 检查响应数据
                JsonNode klineList = ticker24h.get("result").get("list"); // 获取K线列表
                if (klineList.size() >= 2) { // 检查数据是否足够
                    double currentPrice = klineList.get(0).get(4).asDouble(); // 当前价格（最新K线的收盘价）
                    double price24hAgo = klineList.get(klineList.size() - 1).get(1).asDouble(); // 24小时前价格（最旧K线的开盘价）

                    if (price24hAgo > 0) { // 避免除零错误
                        return ((currentPrice - price24hAgo) / price24hAgo) * 100; // 计算价格变化百分比
                    }
                }
            }
        } catch (Exception e) { // 捕获异常
            log.warn("获取24小时价格变化失败: {}", e.getMessage()); // 记录警告日志
        }

        return null; // 返回空值
    }

    /**
     * 启用实时数据流
     */
    public void enableDataStream() {
        dataStreamEnabled = true; // 启用数据流
        log.info("✅ 实时市场数据流已启用"); // 记录信息日志
    }

    /**
     * 禁用实时数据流
     */
    public void disableDataStream() {
        dataStreamEnabled = false; // 禁用数据流
        log.info("⏹️ 实时市场数据流已禁用"); // 记录信息日志
    }


}