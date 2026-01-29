package com.trading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.aliyun.SimpleMarkdownBuilder;
import com.trading.entity.*;
import com.trading.model.MarketData;
import com.trading.model.PortfolioStatus;
import com.trading.model.TradingDecision;
import com.trading.repository.*;
import com.trading.util.DingDingMessageUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * DeepSeek服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeepSeekService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final MarketOverviewRepository marketOverviewRepository;
    private final MarketKlineRepository marketKlineRepository;
    private final MarketKline1hRepository marketKline1hRepository;
    private final MarketKlineDailyRepository marketKlineDailyRepository;
    private final MarketKlineWeeklyRepository marketKlineWeeklyRepository;
    private final TradeOrderRepository tradeOrderRepository;
    @Value("${deepseek.api-key}")
    private String deepseekApiKey;
    @Value("${trading.stop-loss-percent}")
    private Double stopLossPercent;

    // 添加依赖注入
    private final AiStrategyRecordRepository aiStrategyRecordRepository;
    private final TraderStrategyService traderStrategyService;                    // ✅ 新增：交易员策略服务（用于取当天策略）

    // 📅 定义统一的时间格式
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final TechnicalIndicatorService indicatorService; // ✅ 新增指标计算服务依赖

    /**
     * 检查是否为备用投资组合数据
     */
    private boolean isFallbackPortfolioData(PortfolioStatus portfolio) {
        // 检查是否为默认的备用数据（总资产接近100，现金接近100，持仓为0）
        return portfolio != null && Math.abs(portfolio.getTotalValue() - 10.0) < 5.0 && Math.abs(portfolio.getCash() - 10.0) < 5.0 && portfolio.getPosition() == 0.0;
    }

    /**
     * 🧠 获取交易决策（确保使用真实投资组合数据）
     * 同时将 AI 推理说明写入大行情分析数据库（market_overview）
     */
    public TradingDecision getTradingDecision(
            MarketData md15m,                  // 15分钟行情数据
            MarketData md1h,                   // 1小时行情数据
            MarketData md1d,                   // 日线行情数据
            MarketData md1w,                   // 周线行情数据
            PortfolioStatus portfolio           // 当前账户投资组合状态
    ) {
        String apiKey = deepseekApiKey;        // 获取 DeepSeek API Key

        // ==================== 🔐 API KEY 校验 ====================
        if (apiKey == null || apiKey.isEmpty()) {                 // 如果 API Key 为空
            log.warn("DeepSeek API密钥未设置，使用备用策略");      // 日志提示
            return getFallbackDecision(md15m);                    // 使用备用策略
        }

        try {
            // ==================== 📊 投资组合数据校验 ====================
            if (isFallbackPortfolioData(portfolio)) {             // 判断是否为备用组合数据
                log.warn("⚠️ AI决策使用备用投资组合数据，可能影响决策准确性"); // 风险提示日志
            }
            // ==================== 🧱 构建 AI 提示词 ====================
            String prompt = buildTradingPrompt(                   // 构建 AI 提示词
                    md15m,                                        // 15分钟行情
                    md1h,                                         // 1小时行情
                    md1d,                                         // 日线行情
                    md1w,                                         // 周线行情
                    portfolio                                     // 投资组合状态
            );
            log.info("📤 发送给DeepSeek的提示词: {}", prompt);      // 打印提示词日志（调试用）


            // ==================== 🤖 调用 AI 接口 ====================
            String response = sendChatRequest(                     // 调用 DeepSeek API
                    prompt,                                       // 提示词
                    apiKey                                        // API Key
            );
            // ==================== 🧠 解析 AI 决策 ====================
            TradingDecision originalDecision = parseAIDecision(    // 解析 AI JSON 响应
                    response,                                     // AI 原始响应
                    md15m,                                        // 当前行情
                    portfolio                                     // 投资组合
            );
            // =========================================================
            // 🧠 Step 1️⃣：写入【大行情分析数据库 market_overview】
            // =========================================================

            MarketOverviewEntity overview = MarketOverviewEntity.builder() // 构建实体
                    .author("DeepSeek-AI分析")                                // 作者来源标识
                    .fullAnalysis(originalDecision.getReasoning())        // AI 推理全文
                    .createdAt(LocalDateTime.now())                       // 当前时间
                    .build();                                             // 构建对象

            marketOverviewRepository.save(overview);                      // 写入数据库

            log.info("🧠 已保存 AI 大行情分析记录 → market_overview | ID={}", overview.getId()); // 成功日志

            // =========================================================
            // 🧠 Step 2️⃣：保存原始 AI 策略记录（你原有逻辑）
            // =========================================================

            AiStrategyRecordEntity strategyRecord =
                    aiStrategyRecordRepository.save(                     // 保存策略记录
                            AiStrategyRecordEntity.builder()              // Builder 构建
                                    .strategyName("DeepSeek-RSI-Strategy")// 策略名称
                                    .signal(originalDecision.getAction()) // AI 动作
                                    .conditionTrigger(originalDecision.getReasoning()) // 推理摘要
                                    .price(BigDecimal.valueOf(md15m.getCurrentPrice())) // 当前价格
                                    .suggestedQty(BigDecimal.valueOf(originalDecision.getPositionSize())) // 建议仓位
                                    .orderQty(BigDecimal.valueOf(originalDecision.getOrderQty())) // 实际下单量
                                    .confidence(BigDecimal.valueOf(originalDecision.getConfidence())) // 置信度
                                    .executionStatus("RAW_DECISION")      // 执行状态
                                    .createdAt(LocalDateTime.now())       // 时间
                                    .build()                              // 构建对象
                    );

            originalDecision.setStrategyRecordId(strategyRecord.getId());  // 绑定策略记录ID

            // ==================== 📢 推送提醒 ====================
            pushMarketAndTraderSummary(md15m.getSymbol());                 // 推送行情+交易员摘要

            log.info("🧠 已保存原始 AI 策略记录 ID={}", strategyRecord.getId()); // 日志

            // ==================== 📊 数据源标记 ====================
            if (isFallbackPortfolioData(portfolio)) {                      // 判断数据来源
                log.info("🤖 AI原始决策基于备用投资组合数据");               // 备用数据日志
            } else {
                log.info("🤖 AI原始决策基于真实投资组合数据");               // 真实数据日志
            }
            return originalDecision;                                       // 返回 AI 决策对象

        } catch (Exception e) {
            // ==================== ❌ 异常处理 ====================
            log.error("DeepSeek API调用失败: {}", e.getMessage(), e);       // 错误日志
            return getFallbackDecision(md15m);                              // 返回备用策略

        }
    }

    // 🆕 新增辅助方法（需要在实际代码中实现）
    private boolean isTestEnvironment() {
        // 实现逻辑：检查是否是测试环境
        // 可以从配置文件中读取，或者根据环境变量判断
        return false; // 示例返回值
    }

    private String getTestTrend() {
        // 实现逻辑：获取测试趋势设置
        // 可以从配置文件中读取，或者从数据库获取
        return "NEUTRAL"; // 示例返回值，可以是 "BULL", "BEAR", "NEUTRAL"
    }

    /**
     * 构建交易提示词 - 强调新的RSI策略
     */
    private String buildTradingPrompt(MarketData md15m, MarketData md1h, MarketData md1d, MarketData md1w, PortfolioStatus portfolio) {
        StringBuilder prompt = new StringBuilder();

        // === 🆕 新增：测试环境特殊处理 ===
        boolean isTestEnvironment = isTestEnvironment(); // 假设有这个方法来检测是否是测试环境
        String testTrend = getTestTrend(); // 假设有这个方法来获取测试趋势设置 ("BULL" 或 "BEAR")

        // === 1️⃣ 基本角色与策略定位 ===
        prompt.append("你是一名专业的量化交易AI，请基于以下【真实市场数据】与【账户持仓信息】，生成交易决策。\n");
        prompt.append("你必须分析K线数据（RSI、MACD、布林带、EMA趋势强度、ATR波动性），大行情分析，交易员观点，持仓方向与盈亏情况，判断是否开空、开多，持仓、加仓、减仓或止盈/止损。\n");
        prompt.append("做空信号（SELL）与做多（BUY）同等重要，根据趋势判断，不要只会单方面做多或做空。\n\n");
        prompt.append("杠杆需根据波动率与信号强度动态调整，最大不超过20倍。\n\n");

        // 🚨 新增：输出格式强制要求
        prompt.append("🚨 输出格式强制要求：\n");
        prompt.append("你的输出必须是【纯JSON格式】，不要包含任何Markdown代码块标记（如```json```）、解释文字、额外说明或其他内容！\n");
        prompt.append("直接输出JSON对象，系统会直接解析你的输出。\n\n");

        // === 🚨 新增：交易规则强制要求（必须放在技术分析之前）===
        prompt.append("=== 🚨 交易规则强制要求 ===\n");
        prompt.append("以下规则**必须优先于所有技术指标分析**，AI必须严格遵守：\n\n");

        // 🎯 止损目标规则 - 防止过早平仓，让利润奔跑
        prompt.append("🎯 止损目标规则（防止过早平仓）：\n");
        prompt.append("1. 设置明确的止损底线（基于持仓盈亏百分比）：\n");
        prompt.append("   - **止损底线**：持仓亏损达到-5%时必须考虑止损\n");
        prompt.append("   - **趋势延续持有**：如果趋势明确且技术指标支持，即使持仓盈利达到2%也应继续持有\n\n");

        // 🆕 新增：趋势自由加仓规则
        prompt.append("🚀 趋势自由加仓规则（让利润奔跑）：\n");
        prompt.append("2. 当趋势明确且持仓方向正确时，允许自由加仓：\n");
        prompt.append("   - **多头趋势加仓条件**：\n");
        prompt.append("     • 主要趋势为多头（EMA多头排列，MACD>0）\n");
        prompt.append("     • 当前持仓为LONG且已实现浮盈\n");
        prompt.append("     • 价格回调至关键支撑位（EMA20、EMA50、EMA144、EMA168、布林带中轨等）\n");
        prompt.append("     • 回调过程中出现止跌信号（锤头线、看涨吞没等）\n");
        prompt.append("     • RSI下跌至20-30超卖区域后重新向上突破30\n"); // 🎯 修正：20-30区域重新向上
        prompt.append("   - **空头趋势加仓条件**：\n");
        prompt.append("     • 主要趋势为空头（EMA空头排列，MACD<0）\n");
        prompt.append("     • 当前持仓为SHORT且已实现浮盈\n");
        prompt.append("     • 价格反弹至关键阻力位（EMA20、EMA50、EMA144、EMA168、布林带中轨等）\n");
        prompt.append("     • 反弹过程中出现滞涨信号（射击之星、看跌吞没等）\n");
        prompt.append("     • RSI上涨至70-80超买区域后重新向下跌破70\n"); // 🎯 修正：70-80区域重新向下
        prompt.append("   - **加仓风险管理**：\n");
        prompt.append("     • 每次加仓后，总体仓位不超过总资金的30%\n");
        prompt.append("     • 加仓后的平均成本应在安全范围内\n");
        prompt.append("     • 设置统一的止损位，保护所有仓位\n");
        prompt.append("     • 加仓间隔应有足够的价格波动空间\n\n");

        // ⚖️ 持仓方向互斥规则 - 保持交易方向一致性
        prompt.append("⚖️ 持仓方向互斥规则：\n");
        prompt.append("3. 持仓方向一致性要求：\n");
        prompt.append("   - 如果账户【持有多单】，禁止输出CLOSE_SHORT，仅允许 HOLD 或 BUY 加仓/ CLOSE_LONG平多\n");
        prompt.append("   - 如果账户【持有空单】，禁止输出CLOSE_LONG，仅允许 HOLD 或 SELL 加仓/ CLOSE_SHORT 平空\n");
        prompt.append("   - 如果账户【无持仓】，根据趋势，可自由决定 BUY、SELL 或 HOLD\n");
        prompt.append("   - 任何违反持仓方向的信号将被视为无效\n\n");

        // === 5️⃣ 实时市场数据 ===
        prompt.append("=== 📊 实时市场数据 ===\n");
        prompt.append(String.format("交易对: %s\n", md1h.getSymbol()));
        prompt.append(String.format("当前价格: $%.2f\n", md15m.getCurrentPrice()));
        // === 24小时价格变化（从数据库计算） ===
        Double change24h = calculatePriceChange24h(md15m.getSymbol());
        if (change24h != null) {
            String emoji = change24h >= 0 ? "📈" : "📉";
            prompt.append(String.format("%s 过去24小时价格变化: %.2f%%\n", emoji, change24h));
        } else {
            prompt.append("📊 过去24小时价格变化: 暂无可用数据\n");
        }

        // === 6️⃣ K线数据（简化逐条输出版） ===
        prompt.append("\n=== 🔍 K线数据 ===\n"); // 标题，AI可识别不同周期行情块

        // 当前时间
        LocalDateTime now = LocalDateTime.now();

        // 周期映射（用于显示名称）
        Map<String, String> nameMap = Map.of("15m", "15分钟", "1h", "1小时", "1d", "1天", "1w", "1周");

        // === 1️⃣ 定义时间区间 ===
        LocalDateTime from15m = now.minusDays(1);    // 15分钟 → 最近1天
        LocalDateTime from1h = now.minusDays(3);    // 1小时 → 最近3天
        LocalDateTime from1d = now.minusDays(14);   // 1天 → 最近14天
        LocalDateTime from1w = now.minusMonths(2);  // 1周 → 最近2个月

        // === 2️⃣ 逐周期查询数据库 ===
        // ========================================================================
        // ===================== 15分钟周期（最近1天） ===========================
        // ========================================================================
        List<MarketKlineEntity> all15m = marketKlineRepository
                .findBySymbolOrderByOpenTimeAsc(md15m.getSymbol());                                  // ✅ 获取全部15分钟历史数据
        prompt.append("\n📘 [15分钟K线 - 最近1天 + 技术指标]\n");                          // 添加区块标题

        List<Double> closes15m = new ArrayList<>();                                       // 收盘价列表
        List<Double> highs15m = new ArrayList<>();                                        // 最高价列表
        List<Double> lows15m = new ArrayList<>();                                         // 最低价列表

        for (MarketKlineEntity k : all15m) {                                              // 遍历所有K线（全量用于计算指标）
            closes15m.add(k.getClose());                                                  // 添加收盘价
            highs15m.add(k.getHigh());                                                    // 添加最高价
            lows15m.add(k.getLow());                                                      // 添加最低价

            // === 技术指标计算（基于全量） ===
            double ema20 = indicatorService.calculateEMA(closes15m, 20);                 // EMA20
            double ema50 = indicatorService.calculateEMA(closes15m, 50);                 // EMA50
            // 🆕 新增：长周期EMA计算
            double ema144 = indicatorService.calculateEMA(closes15m, 144);               // EMA144
            double ema168 = indicatorService.calculateEMA(closes15m, 168);               // EMA168
            double ema288 = indicatorService.calculateEMA(closes15m, 288);               // EMA288
            double ema338 = indicatorService.calculateEMA(closes15m, 338);               // EMA338
            double rsi14 = indicatorService.calculateRSI(closes15m, 14);                 // RSI14
            TechnicalIndicatorService.MACDResult macd = indicatorService.calculateMACD(closes15m, 12, 26, 9); // MACD
            double atr14 = indicatorService.calculateATR(highs15m, lows15m, closes15m, 14); // ATR14
            double bbPos = indicatorService.calculateBollingerBandsPosition(closes15m, 20); // 布林带位置
            double bbWidth = indicatorService.calculateBBBandwidth(closes15m, 20);        // 布林带带宽

            // === 拼接内容仅限时间区间 ===
            if (!k.getOpenTime().isBefore(from15m) && !k.getOpenTime().isAfter(now)) {
                prompt.append(String.format("时间: %s\n", k.getOpenTime()));
                prompt.append(String.format("价格: 开%.2f 高%.2f 低%.2f 收%.2f 量%.2f\n",
                        k.getOpen(), k.getHigh(), k.getLow(), k.getClose(), k.getVolume()));
                // RSI指标
                prompt.append(String.format("📊 RSI(14): %.2f → %s\n", rsi14, getRSISignalDescription(rsi14)));
                // MACD指标
                String macdStatus = getMACDStatus(macd.getDif(), macd.getDea());
                prompt.append(String.format("🔄 MACD: DIF=%.3f, DEA=%.3f, Histogram=%.3f %s\n",
                        macd.getDif(), macd.getDea(), macd.getHistogram(), macdStatus));
                // EMA指标
                String emaTrend = ema20 > ema50 ? "上升趋势" : "下降趋势";
                prompt.append(String.format("📉 EMA20,50指标和短中期趋势: EMA20=%.2f, EMA50=%.2f → 当前为%s\n",
                        ema20, ema50, emaTrend));
                // 长周期EMA系列
                prompt.append("📊 EMA144,168,288,338长周期趋势指标：\n");
                prompt.append(String.format("   🔹 EMA144 = %.2f (长期趋势基准)\n", ema144));
                prompt.append(String.format("   🔹 EMA168 = %.2f (扩展趋势)\n", ema168));
                prompt.append(String.format("   🔹 EMA288 = %.2f (结构趋势)\n", ema288));
                prompt.append(String.format("   🔹 EMA338 = %.2f (超长趋势)\n", ema338));
                // EMA多层级排列分析
                boolean fullBullTrend = ema20 > ema50 && ema50 > ema144 && ema144 > ema288 && ema288 > ema338;
                boolean fullBearTrend = ema20 < ema50 && ema50 < ema144 && ema144 < ema288 && ema288 < ema338;

                if (fullBullTrend) {
                    prompt.append("   🟢 完整多头均线排列，所有周期趋势强劲上行。\n");
                } else if (fullBearTrend) {
                    prompt.append("   🔴 完整空头均线排列，所有周期趋势明显下行。\n");
                } else if (ema20 > ema50 && ema50 > ema144) {
                    prompt.append("   🟡 短中期多头排列，但长期趋势需要确认。\n");
                } else if (ema20 < ema50 && ema50 < ema144) {
                    prompt.append("   🟠 短中期空头排列，但长期趋势需要确认。\n");
                } else {
                    prompt.append("   ⚪ 均线结构混乱，可能处于大级别震荡整理阶段。\n");
                }
                // 价格相对于长周期EMA的位置分析
                double currentPrice = k.getClose(); // 使用当前K线的收盘价
                String priceVsEma144 = currentPrice > ema144 ? "价格在EMA144之上" : "价格在EMA144之下";
                prompt.append(String.format("   📍 %s (EMA144: %.2f)\n", priceVsEma144, ema144));
                String priceVsEma288 = currentPrice > ema288 ? "价格在EMA288之上" : "价格在EMA288之下";
                prompt.append(String.format("   📍 %s (EMA288: %.2f)\n", priceVsEma288, ema288));

                // 布林带指标
                prompt.append(String.format("📈 布林带位置=%.1f%%, 带宽=%.1f%%\n", bbPos, bbWidth));

                // ATR波动性指标
                prompt.append(String.format("🌪️ ATR(14)=%.4f\n", atr14));

                prompt.append("─".repeat(40) + "\n");
            }
        }

        // ========================================================================
        // ===================== 1小时周期（最近3天） =============================
        // ========================================================================
        List<MarketKline1hEntity> all1h = marketKline1hRepository
                .findBySymbolOrderByOpenTimeAsc(md1h.getSymbol());                                 // ✅ 获取全部1小时历史数据
        prompt.append("\n📗 [1小时K线 - 最近3天 + 技术指标]\n");                           // 添加标题

        List<Double> closes1h = new ArrayList<>();
        List<Double> highs1h = new ArrayList<>();
        List<Double> lows1h = new ArrayList<>();

        for (MarketKline1hEntity k : all1h) {
            closes1h.add(k.getClose());
            highs1h.add(k.getHigh());
            lows1h.add(k.getLow());

            double ema20 = indicatorService.calculateEMA(closes1h, 20);
            double ema50 = indicatorService.calculateEMA(closes1h, 50);
            // 🆕 新增：长周期EMA计算
            double ema144 = indicatorService.calculateEMA(closes1h, 144);
            double ema168 = indicatorService.calculateEMA(closes1h, 168);
            double ema288 = indicatorService.calculateEMA(closes1h, 288);
            double ema338 = indicatorService.calculateEMA(closes1h, 338);
            double rsi14 = indicatorService.calculateRSI(closes1h, 14);
            TechnicalIndicatorService.MACDResult macd = indicatorService.calculateMACD(closes1h, 12, 26, 9);
            double atr14 = indicatorService.calculateATR(highs1h, lows1h, closes1h, 14);
            double bbPos = indicatorService.calculateBollingerBandsPosition(closes1h, 20);
            double bbWidth = indicatorService.calculateBBBandwidth(closes1h, 20);

            if (!k.getOpenTime().isBefore(from1h) && !k.getOpenTime().isAfter(now)) {
                prompt.append(String.format("时间: %s\n", k.getOpenTime()));
                prompt.append(String.format("价格: 开%.2f 高%.2f 低%.2f 收%.2f\n",
                        k.getOpen(), k.getHigh(), k.getLow(), k.getClose()));

                // RSI指标
                prompt.append(String.format("📊 RSI(14): %.2f → %s\n", rsi14, getRSISignalDescription(rsi14)));

                // MACD指标
                String macdStatus = getMACDStatus(macd.getDif(), macd.getDea());
                prompt.append(String.format("🔄 MACD: DIF=%.3f, DEA=%.3f, Histogram=%.3f %s\n",
                        macd.getDif(), macd.getDea(), macd.getHistogram(), macdStatus));

                // EMA指标
                String emaTrend = ema20 > ema50 ? "上升趋势" : "下降趋势";
                prompt.append(String.format("📉 EMA20,50指标和短中期趋势: EMA20=%.2f, EMA50=%.2f → 当前为%s\n",
                        ema20, ema50, emaTrend));

                // 长周期EMA系列
                prompt.append("📊 EMA144,168,288,338长周期趋势指标：\n");
                prompt.append(String.format("   🔹 EMA144 = %.2f (长期趋势基准)\n", ema144));
                prompt.append(String.format("   🔹 EMA168 = %.2f (扩展趋势)\n", ema168));
                prompt.append(String.format("   🔹 EMA288 = %.2f (结构趋势)\n", ema288));
                prompt.append(String.format("   🔹 EMA338 = %.2f (超长趋势)\n", ema338));

                // EMA多层级排列分析
                boolean fullBullTrend = ema20 > ema50 && ema50 > ema144 && ema144 > ema288 && ema288 > ema338;
                boolean fullBearTrend = ema20 < ema50 && ema50 < ema144 && ema144 < ema288 && ema288 < ema338;

                if (fullBullTrend) {
                    prompt.append("   🟢 完整多头均线排列，所有周期趋势强劲上行。\n");
                } else if (fullBearTrend) {
                    prompt.append("   🔴 完整空头均线排列，所有周期趋势明显下行。\n");
                } else if (ema20 > ema50 && ema50 > ema144) {
                    prompt.append("   🟡 短中期多头排列，但长期趋势需要确认。\n");
                } else if (ema20 < ema50 && ema50 < ema144) {
                    prompt.append("   🟠 短中期空头排列，但长期趋势需要确认。\n");
                } else {
                    prompt.append("   ⚪ 均线结构混乱，可能处于大级别震荡整理阶段。\n");
                }

                // 价格相对于长周期EMA的位置分析
                double currentPrice = k.getClose();
                String priceVsEma144 = currentPrice > ema144 ? "价格在EMA144之上" : "价格在EMA144之下";
                prompt.append(String.format("   📍 %s (EMA144: %.2f)\n", priceVsEma144, ema144));
                String priceVsEma288 = currentPrice > ema288 ? "价格在EMA288之上" : "价格在EMA288之下";
                prompt.append(String.format("   📍 %s (EMA288: %.2f)\n", priceVsEma288, ema288));

                // 布林带指标
                prompt.append(String.format("📈 布林带位置=%.1f%%, 带宽=%.1f%%\n", bbPos, bbWidth));

                // ATR波动性指标
                prompt.append(String.format("🌪️ ATR(14)=%.4f\n", atr14));

                prompt.append("─".repeat(40) + "\n");
            }
        }

        // ========================================================================
        // ===================== 日线周期（最近14天） =============================
        // ========================================================================
        List<MarketKlineDailyEntity> all1d = marketKlineDailyRepository
                .findBySymbolOrderByOpenTimeAsc(md1d.getSymbol());                                 // ✅ 获取全部日线数据
        prompt.append("\n📙 [日线K线 - 最近14天 + 技术指标]\n");                           // 添加标题

        List<Double> closes1d = new ArrayList<>();
        List<Double> highs1d = new ArrayList<>();
        List<Double> lows1d = new ArrayList<>();

        for (MarketKlineDailyEntity k : all1d) {
            closes1d.add(k.getClose());
            highs1d.add(k.getHigh());
            lows1d.add(k.getLow());

            double ema20 = indicatorService.calculateEMA(closes1d, 20);
            double ema50 = indicatorService.calculateEMA(closes1d, 50);
            // 🆕 新增：长周期EMA计算
            double ema144 = indicatorService.calculateEMA(closes1d, 144);
            double ema168 = indicatorService.calculateEMA(closes1d, 168);
            double ema288 = indicatorService.calculateEMA(closes1d, 288);
            double ema338 = indicatorService.calculateEMA(closes1d, 338);
            double rsi14 = indicatorService.calculateRSI(closes1d, 14);
            TechnicalIndicatorService.MACDResult macd = indicatorService.calculateMACD(closes1d, 12, 26, 9);
            double atr14 = indicatorService.calculateATR(highs1d, lows1d, closes1d, 14);
            double bbPos = indicatorService.calculateBollingerBandsPosition(closes1d, 20);
            double bbWidth = indicatorService.calculateBBBandwidth(closes1d, 20);

            if (!k.getOpenTime().isBefore(from1d) && !k.getOpenTime().isAfter(now)) {
                prompt.append(String.format("时间: %s\n", k.getOpenTime()));
                prompt.append(String.format("价格: 开%.2f 高%.2f 低%.2f 收%.2f\n",
                        k.getOpen(), k.getHigh(), k.getLow(), k.getClose()));

                // RSI指标
                prompt.append(String.format("📊 RSI(14): %.2f → %s\n", rsi14, getRSISignalDescription(rsi14)));

                // MACD指标
                String macdStatus = getMACDStatus(macd.getDif(), macd.getDea());
                prompt.append(String.format("🔄 MACD: DIF=%.3f, DEA=%.3f, Histogram=%.3f %s\n",
                        macd.getDif(), macd.getDea(), macd.getHistogram(), macdStatus));

                // EMA指标
                String emaTrend = ema20 > ema50 ? "上升趋势" : "下降趋势";
                prompt.append(String.format("📉 EMA20,50指标和短中期趋势: EMA20=%.2f, EMA50=%.2f → 当前为%s\n",
                        ema20, ema50, emaTrend));

                // 长周期EMA系列
                prompt.append("📊 EMA144,168,288,338长周期趋势指标：\n");
                prompt.append(String.format("   🔹 EMA144 = %.2f (长期趋势基准)\n", ema144));
                prompt.append(String.format("   🔹 EMA168 = %.2f (扩展趋势)\n", ema168));
                prompt.append(String.format("   🔹 EMA288 = %.2f (结构趋势)\n", ema288));
                prompt.append(String.format("   🔹 EMA338 = %.2f (超长趋势)\n", ema338));

                // EMA多层级排列分析
                boolean fullBullTrend = ema20 > ema50 && ema50 > ema144 && ema144 > ema288 && ema288 > ema338;
                boolean fullBearTrend = ema20 < ema50 && ema50 < ema144 && ema144 < ema288 && ema288 < ema338;

                if (fullBullTrend) {
                    prompt.append("   🟢 完整多头均线排列，所有周期趋势强劲上行。\n");
                } else if (fullBearTrend) {
                    prompt.append("   🔴 完整空头均线排列，所有周期趋势明显下行。\n");
                } else if (ema20 > ema50 && ema50 > ema144) {
                    prompt.append("   🟡 短中期多头排列，但长期趋势需要确认。\n");
                } else if (ema20 < ema50 && ema50 < ema144) {
                    prompt.append("   🟠 短中期空头排列，但长期趋势需要确认。\n");
                } else {
                    prompt.append("   ⚪ 均线结构混乱，可能处于大级别震荡整理阶段。\n");
                }

                // 价格相对于长周期EMA的位置分析
                double currentPrice = k.getClose();
                String priceVsEma144 = currentPrice > ema144 ? "价格在EMA144之上" : "价格在EMA144之下";
                prompt.append(String.format("   📍 %s (EMA144: %.2f)\n", priceVsEma144, ema144));
                String priceVsEma288 = currentPrice > ema288 ? "价格在EMA288之上" : "价格在EMA288之下";
                prompt.append(String.format("   📍 %s (EMA288: %.2f)\n", priceVsEma288, ema288));

                // 布林带指标
                prompt.append(String.format("📈 布林带位置=%.1f%%, 带宽=%.1f%%\n", bbPos, bbWidth));

                // ATR波动性指标
                prompt.append(String.format("🌪️ ATR(14)=%.4f\n", atr14));

                prompt.append("─".repeat(40) + "\n");
            }
        }

        // ========================================================================
        // ===================== 周线周期（最近2个月） =============================
        // ========================================================================
        List<MarketKlineWeeklyEntity> all1w = marketKlineWeeklyRepository
                .findBySymbolOrderByOpenTimeAsc(md1w.getSymbol());                                 // ✅ 获取全部周线数据
        prompt.append("\n📒 [周线K线 - 最近2个月 + 技术指标]\n");                           // 添加标题

        List<Double> closes1w = new ArrayList<>();
        List<Double> highs1w = new ArrayList<>();
        List<Double> lows1w = new ArrayList<>();

        for (MarketKlineWeeklyEntity k : all1w) {
            closes1w.add(k.getClose());
            highs1w.add(k.getHigh());
            lows1w.add(k.getLow());

            double ema20 = indicatorService.calculateEMA(closes1w, 20);
            double ema50 = indicatorService.calculateEMA(closes1w, 50);
            // 🆕 新增：长周期EMA计算
            double ema144 = indicatorService.calculateEMA(closes1w, 144);
            double ema168 = indicatorService.calculateEMA(closes1w, 168);
            double ema288 = indicatorService.calculateEMA(closes1w, 288);
            double ema338 = indicatorService.calculateEMA(closes1w, 338);
            double rsi14 = indicatorService.calculateRSI(closes1w, 14);
            TechnicalIndicatorService.MACDResult macd = indicatorService.calculateMACD(closes1w, 12, 26, 9);
            double atr14 = indicatorService.calculateATR(highs1w, lows1w, closes1w, 14);
            double bbPos = indicatorService.calculateBollingerBandsPosition(closes1w, 20);
            double bbWidth = indicatorService.calculateBBBandwidth(closes1w, 20);

            if (!k.getOpenTime().isBefore(from1w) && !k.getOpenTime().isAfter(now)) {
                prompt.append(String.format("时间: %s\n", k.getOpenTime()));
                prompt.append(String.format("价格: 开%.2f 高%.2f 低%.2f 收%.2f\n",
                        k.getOpen(), k.getHigh(), k.getLow(), k.getClose()));

                // RSI指标
                prompt.append(String.format("📊 RSI(14): %.2f → %s\n", rsi14, getRSISignalDescription(rsi14)));

                // MACD指标
                String macdStatus = getMACDStatus(macd.getDif(), macd.getDea());
                prompt.append(String.format("🔄 MACD: DIF=%.3f, DEA=%.3f, Histogram=%.3f %s\n",
                        macd.getDif(), macd.getDea(), macd.getHistogram(), macdStatus));

                // EMA指标
                String emaTrend = ema20 > ema50 ? "上升趋势" : "下降趋势";
                prompt.append(String.format("📉 EMA20,50指标和短中期趋势: EMA20=%.2f, EMA50=%.2f → 当前为%s\n",
                        ema20, ema50, emaTrend));

                // 长周期EMA系列
                prompt.append("📊 EMA144,168,288,338长周期趋势指标：\n");
                prompt.append(String.format("   🔹 EMA144 = %.2f (长期趋势基准)\n", ema144));
                prompt.append(String.format("   🔹 EMA168 = %.2f (扩展趋势)\n", ema168));
                prompt.append(String.format("   🔹 EMA288 = %.2f (结构趋势)\n", ema288));
                prompt.append(String.format("   🔹 EMA338 = %.2f (超长趋势)\n", ema338));

                // EMA多层级排列分析
                boolean fullBullTrend = ema20 > ema50 && ema50 > ema144 && ema144 > ema288 && ema288 > ema338;
                boolean fullBearTrend = ema20 < ema50 && ema50 < ema144 && ema144 < ema288 && ema288 < ema338;

                if (fullBullTrend) {
                    prompt.append("   🟢 完整多头均线排列，所有周期趋势强劲上行。\n");
                } else if (fullBearTrend) {
                    prompt.append("   🔴 完整空头均线排列，所有周期趋势明显下行。\n");
                } else if (ema20 > ema50 && ema50 > ema144) {
                    prompt.append("   🟡 短中期多头排列，但长期趋势需要确认。\n");
                } else if (ema20 < ema50 && ema50 < ema144) {
                    prompt.append("   🟠 短中期空头排列，但长期趋势需要确认。\n");
                } else {
                    prompt.append("   ⚪ 均线结构混乱，可能处于大级别震荡整理阶段。\n");
                }

                // 价格相对于长周期EMA的位置分析
                double currentPrice = k.getClose();
                String priceVsEma144 = currentPrice > ema144 ? "价格在EMA144之上" : "价格在EMA144之下";
                prompt.append(String.format("   📍 %s (EMA144: %.2f)\n", priceVsEma144, ema144));
                String priceVsEma288 = currentPrice > ema288 ? "价格在EMA288之上" : "价格在EMA288之下";
                prompt.append(String.format("   📍 %s (EMA288: %.2f)\n", priceVsEma288, ema288));

                // 布林带指标
                prompt.append(String.format("📈 布林带位置=%.1f%%, 带宽=%.1f%%\n", bbPos, bbWidth));

                // ATR波动性指标
                prompt.append(String.format("🌪️ ATR(14)=%.4f\n", atr14));

                prompt.append("─".repeat(40) + "\n");
            }
        }

        // === 7️⃣ 当前账户与持仓状态（完善版） ===
        prompt.append("\n=== 💼 账户与持仓状态 ===\n");
        // 获取当前价格和开仓价格
        double currentPrice = md15m.getCurrentPrice();  // 当前市场价格
        double entryPrice = portfolio.getEntryPrice() != null ? portfolio.getEntryPrice() : currentPrice;  // 开仓均价，若无则用当前价
        // 🏦 总资产
        prompt.append(String.format("总资产(Equity): $%.2f\n",
                portfolio.getTotalValue() != null ? portfolio.getTotalValue() : 0.0));
        // 💵 可用余额
        prompt.append(String.format("可用现金(Available): $%.2f\n",
                portfolio.getCash() != null ? portfolio.getCash() : 0.0));
        // 📈 当前持仓方向（多头 / 空头 / NONE）
        prompt.append(String.format("持仓方向(Direction): %s\n",
                portfolio.getDirection() != null ? portfolio.getDirection() : "NONE"));
        // 📊 当前持仓数量
        prompt.append(String.format("持仓数量(Position Size): %.4f %s\n",
                portfolio.getPosition() != null ? portfolio.getPosition() : 0.0,
                md15m.getSymbol()));
        // 🎯 开仓均价
        prompt.append(String.format("开仓均价(Entry Price): $%.2f\n",
                portfolio.getEntryPrice() != null ? portfolio.getEntryPrice() : 0.0));
        // 💹 标记价格（Bybit最新价）
        prompt.append(String.format("标记价格(Mark Price): $%.2f\n",
                portfolio.getMarkPrice() != null ? portfolio.getMarkPrice() : 0.0));
        // 🧮 当前占用保证金
        prompt.append(String.format("占用保证金(Margin Used): $%.2f\n",
                portfolio.getMarginUsed() != null ? portfolio.getMarginUsed() : 0.0));
        // ⚠️ 强平价格
        prompt.append(String.format("强平价格(Liquidation Price): $%.2f\n",
                portfolio.getLiquidationPrice() != null ? portfolio.getLiquidationPrice() : 0.0));
        // 📉 未实现盈亏金额
        double pnlValue = portfolio.getUnrealisedPnL() != null ? portfolio.getUnrealisedPnL() : 0.0;
        String pnlEmoji = pnlValue >= 0 ? "🟢 盈利" : "🔴 亏损";
        prompt.append(String.format("未实现盈亏金额(Unrealised PnL): %s $%.2f\n", pnlEmoji, pnlValue));
        // 📈 保证金收益率
        double pnlPercent = portfolio.getPnLPercent() != null ? portfolio.getPnLPercent() : 0.0;
        String pnlPercentEmoji = pnlPercent >= 0 ? "🟢" : "🔴";
        prompt.append(String.format("保证金收益率(Margin ROI): %s %.2f%%\n", pnlPercentEmoji, pnlPercent));

        // 🕒 新增：查询最新未平仓订单持仓时间
        TradeOrderEntity latestOpen = tradeOrderRepository
                .findTop1BySymbolAndClosedFalseOrderByCreatedAtDesc(md1h.getSymbol())
                .orElse(null);

        if (latestOpen != null) {
            LocalDateTime openTime = latestOpen.getCreatedAt();
            LocalDateTime nowTime = LocalDateTime.now();

            // 计算时间差
            long totalMinutes = java.time.Duration.between(openTime, nowTime).toMinutes();
            long hours = totalMinutes / 60;
            long minutes = totalMinutes % 60;
            long days = hours / 24;
            long remainHours = hours % 24;

            String durationText;
            if (days > 0) {
                durationText = String.format("%d天%d小时%d分钟", days, remainHours, minutes);
            } else if (hours > 0) {
                durationText = String.format("%d小时%d分钟", hours, minutes);
            } else {
                durationText = String.format("%d分钟", minutes);
            }

            prompt.append(String.format("持仓建立时间: %s （已持仓 %s）\n",
                    openTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    durationText));
        } else {
            prompt.append("持仓建立时间: 无持仓记录\n");
        }
        // === 8️⃣ 风控与止损提醒 ===
        String direction = portfolio.getDirection() != null ? portfolio.getDirection().toUpperCase() : "NONE"; // 获取持仓方向（可能为 LONG / SHORT / NONE）

        prompt.append("\n=== ⚠️ 风控提示 ===\n");
        if ("SHORT".equals(direction) || "空头".equalsIgnoreCase(portfolio.getDirection())) {
            // 🟥 空头止损逻辑：价格上涨触发止损
            double stopLossPrice = entryPrice * (1 + stopLossPercent);
            prompt.append(String.format("📈 空头向上止损触发价: $%.2f (+%.1f%%)\n", stopLossPrice, stopLossPercent * 100));
            prompt.append("说明：若市场价格上涨超过该价位，空头将产生亏损，应立即止损离场。\n");
        } else if ("LONG".equals(direction) || "多头".equalsIgnoreCase(portfolio.getDirection())) {
            // 🟩 多头止损逻辑：价格下跌触发止损
            double stopLossPrice = entryPrice * (1 - stopLossPercent);
            prompt.append(String.format("📉 多头向下止损触发价: $%.2f (−%.1f%%)\n", stopLossPrice, stopLossPercent * 100));
            prompt.append("说明：若市场价格跌破该价位，多头将产生较大亏损，应立即止损离场。\n");
        } else {
            // 无持仓，仅展示风险区间
            double stopLossDown = entryPrice * (1 - stopLossPercent);
            double stopLossUp = entryPrice * (1 + stopLossPercent);
            prompt.append(String.format("🟡 当前无持仓，建议关注风险区间: $%.2f - $%.2f (±%.1f%%)\n", stopLossDown, stopLossUp, stopLossPercent * 100));
        }
        // === 📈 当年交易统计 ===
        LocalDateTime nowTime = LocalDateTime.now();                         // 当前时间
        LocalDateTime startOfYear = LocalDate.now().withDayOfYear(1)         // 今年第一天
                .atStartOfDay();                                             // 今年 01-01 00:00:00

        List<TradeOrderEntity> todayOrders = tradeOrderRepository.findBySymbolAndCreatedAtBetweenOrderByCreatedAtDesc(
                md1h.getSymbol(),
                startOfYear,
                nowTime
        );

        // ✅ 统计「开仓」次数：未平仓（closed=false 或 null），side=BUY/SELL
        long openCount = todayOrders.stream()
                .filter(o -> o.getSide() != null)
                .filter(o -> "BUY".equalsIgnoreCase(o.getSide()) || "SELL".equalsIgnoreCase(o.getSide()))
                .filter(o -> o.getClosed() == null || !o.getClosed())
                .count();

        // ✅ 统计「平仓」次数：已平仓（closed=true）
        long closeCount = todayOrders.stream()
                .filter(o -> Boolean.TRUE.equals(o.getClosed()))
                .count();

        // ✅ 基于已平仓单计算胜率（方向性判断，不用金额）
        List<TradeOrderEntity> closedOrders = todayOrders.stream()
                .filter(o -> Boolean.TRUE.equals(o.getClosed()))
                .toList();

        long winCount = closedOrders.stream().filter(o -> {
            if (o.getAvgEntryPrice() == null || o.getCloseAmount() == null || o.getSide() == null) return false;
            BigDecimal entry = o.getAvgEntryPrice();
            BigDecimal close = o.getCloseAmount();
            // BUY: close > entry 为赢；SELL: close < entry 为赢
            if ("BUY".equalsIgnoreCase(o.getSide())) {
                return close.compareTo(entry) > 0;
            } else if ("SELL".equalsIgnoreCase(o.getSide())) {
                return close.compareTo(entry) < 0;
            }
            return false;
        }).count();

        long lossCount = closedOrders.size() - winCount;
        double winRate = (winCount + lossCount) > 0 ? (winCount * 100.0 / (winCount + lossCount)) : 0.0;

        // === 📊 当日交易统计输出（不含任何金额汇总） ===
        String currentYear = String.valueOf(LocalDate.now().getYear());
        prompt.append("\n=== 📊 " + currentYear + "年交易活动统计 ===\n");
        prompt.append(String.format(currentYear + "年开仓次数: %d 次\n", openCount));
        prompt.append(String.format(currentYear + "平仓次数: %d 次\n", closeCount));
        prompt.append(String.format(currentYear + "年胜率: %.1f%% (盈利%d单 / 亏损%d单)\n", winRate, winCount, lossCount));
        prompt.append("⚖️ 若当日胜率走低，AI应降低杠杆与仓位，优先考虑风险控制或HOLD。\n");


        // === 📄 今年详细下单记录（不展示盈亏金额，只给方向性收益率） ===
        if (!todayOrders.isEmpty()) {
            prompt.append("\n=== 📄 " + currentYear + "年详细下单记录 ===\n");
            DateTimeFormatter hhmmss = DateTimeFormatter.ofPattern("HH:mm:ss");

            for (TradeOrderEntity order : todayOrders) {
                // 标识
                boolean isClosed = Boolean.TRUE.equals(order.getClosed());
                String emoji = isClosed ? "📘 平仓" : "📈 开仓";
                String dirText = "BUY".equalsIgnoreCase(order.getSide()) ? "🟢 多头" :
                        "SELL".equalsIgnoreCase(order.getSide()) ? "🔴 空头" : "⚪ 未知";

                // 状态文案
                String statusText = isClosed ? "✅ 已平仓" : "🕒 持仓中";

                // 直接使用当前浮动盈亏（更实时）
                String estRoiText = "--";
                if (order.getPnlPercent() != null) {
                    // 数据库存储为小数（如 -0.025）转换为百分比
                    estRoiText = String.format("%+.2f%%", order.getPnlPercent().doubleValue());
                }

                prompt.append(String.format(
                        "%s | %s | %s | 数量: %.4f | 开仓价: %.2f | 平仓价: %.2f | 当前浮动盈亏: %s | 杠杆: %.1fx | 状态: %s | 时间: %s  \n",
                        emoji,
                        dirText,
                        order.getSymbol(),
                        order.getQty() != null ? order.getQty().doubleValue() : 0.0,
                        order.getAvgEntryPrice() != null ? order.getAvgEntryPrice().doubleValue() : 0.0,
                        order.getCloseAmount() != null ? order.getCloseAmount().doubleValue() : 0.0,
                        estRoiText,
                        order.getLeverage() != null ? order.getLeverage().doubleValue() : 0.0,
                        statusText,
                        order.getCreatedAt().format(hhmmss)
                ));
            }
        } else {
            prompt.append(currentYear + "年暂无下单记录。\n");
        }

        // 🆕 从 Service 中一次性获取「每个作者最新的一条大行情分析」         // 使用你刚写的 Service
        List<MarketOverviewEntity> overviews = marketOverviewRepository.findLatestRecordOfEachAuthor(); // 不按时间，只取每作者最新

        if (overviews == null || overviews.isEmpty()) {                             // 判空：没有任何大行情记录
            prompt.append("无大行情分析记录（作者最新数据为空）。\n");                  // 提示信息
        } else {
            prompt.append("\n=== 大行情分析 ===\n");
            for (MarketOverviewEntity overview : overviews) {                        // 遍历每个作者的最新一条
                prompt.append(String.format("作者：%s\n", overview.getAuthor()));      // 输出作者名称
                prompt.append(String.format("创建时间：%s\n", overview.getCreatedAt())); // 输出创建时间

                String content = overview.getFullAnalysis();                         // 取分析正文
                if (content != null && !content.trim().isEmpty()) {                  // 如果正文不为空
                    prompt.append("分析内容：\n")                                      // 前缀提示
                            .append(content.trim())                                    // 追加正文
                            .append("\n");                                             // 换行
                }

                prompt.append("—".repeat(50)).append("\n");                          // 分隔线，方便 AI 识别不同作者
            }
        }

        // 输出原始交易员观点供AI解析
        prompt.append("=== 交易员观点 ===\n");
        List<TraderStrategyEntity> todayStrategies =                                    // 拉取当天策略
                traderStrategyService.getTodayStrategiesBySymbol(md1h.getSymbol());     // 通过服务按symbol获取
        if (todayStrategies == null || todayStrategies.isEmpty()) {                     // 若无数据
            prompt.append("无当日交易员策略记录。\n");                                     // 提示无数据
        } else {                                                                        // 否则
            // 统计各方向数量，用于“共识/分歧”提示
            long bull = todayStrategies.stream()                                        // 统计多
                    .filter(s -> "多".equalsIgnoreCase(s.getDirection())                // 方向=多
                            || "LONG".equalsIgnoreCase(s.getDirection()))               // 或英文LONG
                    .count();                                                           // 计数
            long bear = todayStrategies.stream()                                        // 统计空
                    .filter(s -> "空".equalsIgnoreCase(s.getDirection())                // 方向=空
                            || "SHORT".equalsIgnoreCase(s.getDirection()))              // 或英文SHORT
                    .count();                                                           // 计数
            long neutral = todayStrategies.stream()                                     // 统计震荡
                    .filter(s -> "震荡".equalsIgnoreCase(s.getDirection())              // 方向=震荡
                            || "NEUTRAL".equalsIgnoreCase(s.getDirection()))            // 或英文NEUTRAL
                    .count();                                                           // 计数


            // 逐条写入交易员观点（止盈止损为空时不输出）
            for (TraderStrategyEntity s : todayStrategies) {                            // 遍历策略
                String comment = s.getComment() == null ? "" : s.getComment();          // 取备注
                if (comment.length() > 180) {                                           // 超长截断
                    comment = comment.substring(0, 180) + "...";                        // 加省略号
                }

                // 动态拼接止损、止盈字段 —— 空值不输出
                StringBuilder line = new StringBuilder();                               // 新建文本行
                line.append(String.format("- %s：%s，建仓%s",                            // 基本信息
                        s.getTraderName(),                                              // 名称
                        s.getDirection(),                                               // 方向
                        s.getEntryRange()));                                            // 建仓区间

                // 若止损不为空则加入
                if (s.getStopLoss() != null && !s.getStopLoss().trim().isEmpty()) {
                    line.append("，止损").append(s.getStopLoss());
                }

                // 若止盈不为空则加入
                if (s.getTakeProfit() != null && !s.getTakeProfit().trim().isEmpty()) {
                    line.append("，止盈").append(s.getTakeProfit());
                }

                // 加备注
                if (comment != null && !comment.trim().isEmpty()) {
                    line.append("。备注：").append(comment);
                }

                // 加换行
                line.append("\n");
                prompt.append(line.toString());                                         // 拼入主文本
            }

            // 追加“共识统计”
            prompt.append(String.format("【共识统计】多头=%d，空头=%d，震荡=%d。",            // 拼共识统计
                    bull, bear, neutral));                                              // 三类计数
            if (bear > bull && bear >= 2) {                                             // 若空头明显占优
                prompt.append(" 当日意见偏空，请在信号不一致时适度降低仓位。\n");             // 给出建议
            } else if (bull > bear && bull >= 2) {                                      // 若多头明显占优
                prompt.append(" 当日意见偏多，请在冲高回落风险下控制杠杆。\n");               // 给出建议
            } else {                                                                    // 否则
                prompt.append(" 当日意见分歧较大，建议谨慎、以技术信号为主。\n");             // 给出建议
            }
        }

        // === 7️⃣ 多因子综合决策框架 ===
        prompt.append("\n=== 🧠决策框架 ===\n");
        prompt.append("请严格按照以下因素顺序进行综合分析并做出最终决策：\n");
        prompt.append("1. **趋势方向判断**（核心决策依据）：\n");
        prompt.append("   - 多周期EMA排列分析（EMA20>EMA50或EMA144>EMA168为多头，反之空头）\n");
        prompt.append("   - 价格与关键EMA位置关系（价格在EMA20之上短期多头，之下短期空头）\n");
        prompt.append("   - 大行情趋势方向解析结果的参考权重\n\n");
        prompt.append("2. **加仓机会评估**（趋势延续时的重要决策）：\n");
        prompt.append("   - 检查是否符合趋势自由加仓条件：持仓盈利+趋势明确+回调到位\n");
        prompt.append("   - 多头加仓：价格回调至EMA20/50或144/168/布林带中轨+RSI 20-30反弹信号\n");
        prompt.append("   - 空头加仓：价格反弹至EMA20/50或144/168/布林带中轨+RSI 70-80回落信号\n");
        prompt.append("   - 加仓风险管理：总仓位≤30%，每次加仓量≤初始仓位50%\n\n");
        prompt.append("3. **做多做空机会平等分析**：\n");
        prompt.append("   - 同等重视做空机会，避免做多偏好\n");
        prompt.append("   - 空头信号强度评估：下跌趋势+阻力位+RSI超买回落\n");
        prompt.append("   - 多头信号强度评估：上涨趋势+支撑位+RSI超卖反弹\n");
        prompt.append("   - 基于客观信号而非主观偏好选择方向\n\n");
        prompt.append("4. **背离信号分析**（重要反转预警）：\n");
        prompt.append("   - RSI顶背离检测：在RSI 60-90范围内，价格创新高而RSI未创新高\n");
        prompt.append("   - RSI底背离检测：在RSI 10-40范围内，价格创新低而RSI未创新低\n");
        prompt.append("   - 多周期背离协调性分析（多个周期同时出现更可靠）\n");
        prompt.append("   - 背离信号需要价格行为确认才能入场\n\n");
        prompt.append("5. **关键位置分析**（支撑阻力识别）：\n");
        prompt.append("   - 大行情解析提取的关键支撑位和阻力位\n");
        prompt.append("   - 交易员建仓区间集中的价格区域\n");
        prompt.append("   - 技术分析的关键EMA位置和布林带边界\n");
        prompt.append("   - 历史高低点和重要心理价位\n\n");
        prompt.append("6. **入场时机评估**（具体操作时机）：\n");
        prompt.append("   - 顺势入场：主要趋势明确时的回调买入或反弹做空\n");
        prompt.append("   - 关键位置入场：明确支撑位做多或阻力位做空\n");
        prompt.append("   - RSI极值信号入场：RSI≤26轻仓做多或RSI≥72轻仓做空\n");
        prompt.append("   - 确认信号：需要至少2个技术指标支持入场决定\n\n");
        prompt.append("7. **止损目标检查**（基于持仓状态）：\n");
        prompt.append("   - 止损条件：持仓亏损≤-5%或趋势反转确认\n");
        prompt.append("   - 让利润奔跑：趋势强劲时即使盈利2%+也应继续持有\n");
        prompt.append("8. **RSI趋势确认信号**：\n");
        prompt.append("   - RSI超卖反弹：20-30区间向上突破30的多头信号\n");
        prompt.append("   - RSI超买回落：70-80区间向下跌破70的空头信号\n");
        prompt.append("   - RSI中性区域：40-60区间跟随主要趋势方向\n");
        prompt.append("   - 多周期RSI协调性分析\n\n");
        prompt.append("9. **RSI背离可靠性评估**：\n");
        prompt.append("    - 顶背离有效性：RSI在60-90区间内，价格创新高而RSI未创新高\n");
        prompt.append("    - 底背离有效性：RSI在10-40区间内，价格创新低而RSI未创新低\n");
        prompt.append("    - 多周期确认：多个时间周期同时出现背离更可靠\n");
        prompt.append("    - 关键位置验证：背离出现在关键支撑阻力位更有效\n\n");
        prompt.append("10. **MACD趋势支持分析**：\n");
        prompt.append("    - DIF/DEA位置：零轴以上多头，零轴以下空头\n");
        prompt.append("    - 金叉死叉信号：零轴上金叉强烈多头，零轴下死叉强烈空头\n");
        prompt.append("    - 柱状图动能：Histogram放大表示动能增强\n");
        prompt.append("    - 多周期MACD协调性\n\n");
        prompt.append("11. **布林带顺势入场机会**：\n");
        prompt.append("    - 布林带位置：<10%超卖可能反弹，>90%超买可能回落\n");
        prompt.append("    - 带宽变化：带宽收窄预示突破，带宽扩大确认趋势\n");
        prompt.append("    - 中轨支撑压力：价格回调至中轨获得支撑/压力\n");
        prompt.append("    - 顺势交易：沿趋势方向在布林带中轨附近入场\n\n");
        prompt.append("12. **EMA排列趋势确认**：\n");
        prompt.append("    - 短中长期EMA排列：EMA20>EMA50或EMA144>EMA168完整多头\n");
        prompt.append("    - 价格与EMA关系：价格在EMA20之上短期强势\n");
        prompt.append("    - 长周期EMA支撑：EMA144/168/288/338的长期趋势基准\n");
        prompt.append("    - 多周期EMA协调性分析\n\n");
        prompt.append("13. **ATR波动率适应性**：\n");
        prompt.append("    - ATR绝对值：高ATR需要降低仓位和杠杆\n");
        prompt.append("    - ATR相对变化：ATR扩大波动加剧，ATR收窄波动平缓\n");
        prompt.append("    - 杠杆调整：高波动率时3-5倍，低波动率时10-15倍\n");
        prompt.append("    - 止损宽度设置：基于ATR设置合理的止损距离\n\n");
        prompt.append("14. **大行情深度解析结果应用**：\n");
        prompt.append("    - 主要趋势方向：基于作者内容解析的具体趋势判断（多头/空头/震荡）\n");
        prompt.append("    - 关键支撑位：从大行情中提取的具体支撑价格数值列表\n");
        prompt.append("    - 关键阻力位：从大行情中提取的具体阻力价格数值列表\n");
        prompt.append("    - 市场情绪状态：极度乐观/谨慎乐观/悲观/恐慌的情绪评估\n");
        prompt.append("    - 风险因素识别：作者提到的主要风险提示和应对建议\n\n");
        prompt.append("15. **交易员观点量化结果应用**：\n");
        prompt.append("    - 多空分布统计：具体量化多头X人、空头X人、震荡X人的分布\n");
        prompt.append("    - 建仓重心区域：交易员建议建仓的价格区间集中区域\n");
        prompt.append("    - 止损共识位置：普遍设置的止损价位和合理性评估\n");
        prompt.append("    - 止盈目标位置：普遍设置的止盈价位和可实现性评估\n");
        prompt.append("    - 观点共识强度：基于分布统计的市场共识程度判断\n\n");
        prompt.append("16. **价格共振分析结果应用**：\n");
        prompt.append("    - 强共振区域识别：大行情支撑阻力与交易员建仓区间重叠度最高的价格区间\n");
        prompt.append("    - 共振强度等级：强（高度重叠）/中（部分重叠）/弱（轻微重叠）\n");
        prompt.append("    - 最佳交易机会：基于共振分析确定的最优先交易方向和价位\n");
        prompt.append("    - 风险区域标记：需要避开或谨慎对待的价格区间\n");
        prompt.append("    - 操作优先级：共振信号强的机会优先于单一信号\n\n");
        prompt.append("17. **技术分析最终确认**：\n");
        prompt.append("    - 趋势方向最终确认：综合所有因素后的趋势判断\n");
        prompt.append("    - 具体入场时机：精确的入场价格和时机选择\n");
        prompt.append("    - 风险管理方案：具体的仓位比例、杠杆倍数、止损设置\n");
        prompt.append("    - 预期收益目标：短期和中期目标价位设定\n");
        prompt.append("    - 应急预案：市场反向运动时的应对措施\n\n");

        // === 🆕 新增：RSI背离检测说明 ===
        prompt.append("\n=== 🔄 RSI背离检测指南 ===\n");
        prompt.append("请基于【K线数据】仔细分析RSI背离信号：\n\n");

        prompt.append("📉 RSI底背离（Bullish Divergence - 看涨信号）：\n");
        prompt.append("   - 价格走势：形成更低的低点（Lower Low）\n");
        prompt.append("   - RSI走势：形成更高的低点（Higher Low）\n");
        prompt.append("   - 市场含义：下跌动能减弱，可能反转上涨\n");
        prompt.append("   - 确认条件：需要价格突破前高确认\n");
        prompt.append("   - 操作建议：可考虑轻仓做多，设置止损于前低下方\n\n");

        prompt.append("📈 RSI顶背离（Bearish Divergence - 看跌信号）：\n");
        prompt.append("   - 价格走势：形成更高的高点（Higher High）\n");
        prompt.append("   - RSI走势：形成更低的高点（Lower High）\n");
        prompt.append("   - 市场含义：上涨动能减弱，可能反转下跌\n");
        prompt.append("   - 确认条件：需要价格跌破前低确认\n");
        prompt.append("   - 操作建议：可考虑轻仓做空，设置止损于前高上方\n\n");

        prompt.append("🎯 RSI背离交易要点：\n");
        prompt.append("   - 背离信号在主要趋势末端更可靠\n");
        prompt.append("   - 多周期背离（如1小时和4小时同时出现）信号更强\n");
        prompt.append("   - 背离后需要价格行为确认才能入场\n");
        prompt.append("   - 结合其他指标（如MACD、成交量）提高胜率\n");
        prompt.append("   - 在关键支撑/阻力位出现的背离信号更有效\n\n");

        prompt.append("🚨 顺势交易特别注意：\n");
        prompt.append("- **趋势优先**：必须先判断趋势方向，再寻找入场机会\n");
        prompt.append("- **背离预警**：RSI背离信号可作为趋势反转的早期预警\n"); // 🆕 新增背离预警
        prompt.append("- **关键位置机会**：在明确的关键支撑或阻力位可轻仓试探\n"); // 🆕 简化关键位置机会
        prompt.append("- **宁可错过**：趋势不明确时坚决观望，不强行交易\n");
        prompt.append("- **回调入场**：主要趋势明确时，等待回调后入场，不追高杀跌\n");
        prompt.append("- **止损坚决**：趋势判断错误时立即止损，不抱侥幸心理\n");
        prompt.append("- **持盈耐心**：趋势延续时耐心持有，让利润充分增长\n");

        // 🆕 新增：趋势自由加仓的具体判断标准
        prompt.append("🎯 趋势自由加仓判断标准：\n");
        prompt.append("🔹 【多头持仓时的加仓机会】\n");
        prompt.append("   - 主要趋势：EMA多头排列，MACD>0，价格在关键EMA之上\n");
        prompt.append("   - 回调位置：价格回调至EMA20/50或144/168、布林带中轨、前支撑位\n");
        prompt.append("   - 技术信号：RSI下跌至20-30超卖区后重新向上突破30，出现看涨K线形态\n"); // 🎯 修正
        prompt.append("   - 风险管理：当前持仓已盈利，加仓后总仓位≤30%，设置统一止损\n\n");

        prompt.append("🔹 【空头持仓时的加仓机会】\n");
        prompt.append("   - 主要趋势：EMA空头排列，MACD<0，价格在关键EMA之下\n");
        prompt.append("   - 反弹位置：价格反弹至EMA20/50或144/168、布林带中轨、前阻力位\n");
        prompt.append("   - 技术信号：RSI上涨至70-80超买区后重新向下跌破70，出现看跌K线形态\n"); // 🎯 修正
        prompt.append("   - 风险管理：当前持仓已盈利，加仓后总仓位≤30%，设置统一止损\n\n");

        // === 止盈控制 ===
        // === 止盈/加仓/止损控制（优化版）===
        prompt.append("\n=== 💰️ 止盈/加仓/止损控制 ===\n");
        prompt.append("🟢 止盈条件：\n");
        prompt.append("- **趋势反转确认**：无论盈亏多少，趋势反转确认时应立即离场\n"); // 趋势反转时离场
        prompt.append("- **关键阻力位置**：到达关键阻力位且趋势受阻时考虑止盈\n\n"); // 关键位置止盈
        prompt.append("- **做多止盈**：上涨趋势中，价格到达关键阻力位时考虑止盈\n\n"); // 🆕 新增做空止盈
        prompt.append("- **做空止盈**：下跌趋势中，价格到达关键支撑位时考虑止盈\n\n"); // 🆕 新增做空止盈

        prompt.append("🔴 止损条件：\n");
        prompt.append("- **趋势反转确认**：主要趋势发生反转应立即止损\n"); // 趋势反转止损
        prompt.append("- **关键位置突破**：关键支撑或阻力位被突破且趋势改变\n"); // 关键位置突破
        prompt.append("- **持仓亏损%**：无论趋势如何，亏损达到-5%考虑是否止损\n"); // 固定止损
        prompt.append("- **信号失效**：开仓依据的技术信号失效\n\n"); // 信号失效止损
        prompt.append("- **做多止损**：价格下跌突破关键支撑位时应立即止损\n\n"); // 🆕 新增做空止损
        prompt.append("- **做空止损**：价格上涨突破关键阻力位时应立即止损\n\n"); // 🆕 新增做空止损


        prompt.append("🔵 加仓条件：\n");
        prompt.append("- **趋势明确延续**：原趋势方向得到进一步确认，EMA排列保持，MACD动能增强\n");
        prompt.append("- **回调/反弹到位**：\n");
        prompt.append("  • 多头：回调至EMA20/50或144/168、布林带中轨、前支撑位并出现止跌信号\n");
        prompt.append("  • 空头：反弹至EMA20/50或144/168、布林带中轨、前阻力位并出现滞涨信号\n");
        prompt.append("- **技术指标配合**：\n");
        prompt.append("  • 多头：RSI下跌至20-30超卖区后重新向上突破30，MACD保持金叉或二次金叉\n"); // 🎯 修正
        prompt.append("  • 空头：RSI上涨至70-80超买区后重新向下跌破70，MACD保持死叉或二次死叉\n"); // 🎯 修正
        prompt.append("- **做多加仓点位**：上涨趋势中的回调低点，特别是关键支撑位+RSI超卖反弹\n");
        prompt.append("- **做空加仓点位**：下跌趋势中的反弹高点，特别是关键阻力位+RSI超买回落\n");
        prompt.append("- **风险严格控制**：\n");
        prompt.append("  • 加仓后总体仓位≤30%\n");
        prompt.append("  • 每次加仓量≤初始仓位的50%\n");
        prompt.append("  • 设置统一的止损位保护所有仓位\n");
        prompt.append("  • 加仓间隔应有3-5%的价格波动空间\n");
        prompt.append("- **多周期确认**：多个周期同时支持趋势延续，加仓信号协调\n\n");

        // 🆕 新增：RSI加仓信号详细说明
        prompt.append("🎯 RSI加仓信号详解：\n");
        prompt.append("🔹 多头加仓RSI信号流程：\n");
        prompt.append("   1. 主要趋势：多头（EMA多头排列）\n");
        prompt.append("   2. 持仓状态：当前LONG持仓盈利\n");
        prompt.append("   3. 价格行为：价格回调至支撑位\n");
        prompt.append("   4. RSI信号：RSI下跌至20-30区间（超卖）\n");
        prompt.append("   5. 确认信号：RSI从20-30区间重新向上突破30\n");
        prompt.append("   6. 加仓时机：RSI突破30 + 看涨K线确认\n\n");

        prompt.append("🔹 空头加仓RSI信号流程：\n");
        prompt.append("   1. 主要趋势：空头（EMA空头排列）\n");
        prompt.append("   2. 持仓状态：当前SHORT持仓盈利\n");
        prompt.append("   3. 价格行为：价格反弹至阻力位\n");
        prompt.append("   4. RSI信号：RSI上涨至70-80区间（超买）\n");
        prompt.append("   5. 确认信号：RSI从70-80区间重新向下跌破70\n");
        prompt.append("   6. 加仓时机：RSI跌破70 + 看跌K线确认\n\n");

        // === 📏 开仓频率控制 ===
        prompt.append("\n=== 📏 开仓频率控制 ===\n");
        prompt.append("- 根据当日交易统计合理控制开仓频率\n");
        prompt.append("- 若当日交易≥50次，避免重复下单\n");
        prompt.append("- **价格偏差和持仓时间规则已在强制要求中明确，必须优先遵守**\n\n");

        // === 🛡️ 杠杆与仓位管理 ===
        prompt.append("\n=== 🛡️ 杠杆与仓位管理 ===\n");
        prompt.append("杠杆选择原则（基于信号强度和市场波动率）：\n");
        prompt.append("- 强烈信号 + 低波动率: 15-20x\n");
        prompt.append("- 中等信号 + 中等波动率: 8-12x\n");
        prompt.append("- 弱信号 + 高波动率: 3-5x\n");
        prompt.append("- 信号冲突或不确定: 1-3x 或 HOLD\n\n");

        // 🆕 修改：平仓操作说明（AI只返回百分比）
        prompt.append("🎯 平仓操作说明：\n");
        prompt.append("- **CLOSE_LONG/CLOSE_SHORT** 操作表示平仓，close_ratio 表示平仓比例（0.0-1.0）\n");
        prompt.append("- AI只需返回平仓百分比，系统会自动计算实际平仓数量\n");
        prompt.append("- 例如：close_ratio=1.0 表示平掉全部仓位，close_ratio=0.5 表示平掉50%仓位\n");

        prompt.append("🎯 仓位百分比参考：\n"); // 仓位参考标题
        prompt.append("- 保守交易: 1-5%仓位（信号较弱或高波动时）\n"); // 保守仓位范围
        prompt.append("- 中等交易: 5-15%仓位（信号明确且波动适中）\n"); // 中等仓位范围
        prompt.append("- 积极交易: 15-25%仓位（强烈信号且低波动）\n\n"); // 积极仓位范围

        prompt.append("⚡ 杠杆倍数参考：\n"); // 杠杆参考标题
        prompt.append("- 低风险信号: 3-5倍杠杆\n"); // 低风险杠杆范围
        prompt.append("- 中等风险信号: 5-10倍杠杆\n"); // 中等风险杠杆范围
        prompt.append("- 高风险信号: 10-15倍杠杆\n"); // 高风险杠杆范围
        prompt.append("- 极高风险信号: 15-20倍杠杆（需强烈信号支撑）\n\n"); // 极高风险杠杆范围

        prompt.append("🎯 顺势交易核心原则：\n");
        prompt.append("- 趋势不明确：坚决HOLD，不强行交易\n"); // 强调不强行交易
        prompt.append("- 趋势明确：果断开仓，顺势而为\n"); // 强调果断开仓
        prompt.append("- 做空重要：做空与做多同等重要，不要有做多偏好\n"); // 🆕 强调做空重要性
        prompt.append("- 趋势延续：耐心持有，让利润奔跑\n"); // 强调耐心持有
        prompt.append("- 趋势反转：立即止损，保护本金\n"); // 强调立即止损
        prompt.append("- 震荡行情：保持观望，等待突破\n"); // 强调震荡观望


        // === 📋 输出格式要求 - 优化推理结构 ===
        prompt.append("\n=== 📋 输出格式要求 ===\n");
        prompt.append("🚨 重要：你必须只输出【纯JSON格式】，不要包含任何Markdown代码块标记（如```json```）、解释文字或其他内容！\n");
        prompt.append("🚨 系统会直接解析你的输出，任何非JSON内容都会导致解析失败！\n\n");
        prompt.append("{\n");
        prompt.append("  \"action\": \"BUY|SELL|HOLD|CLOSE_LONG|CLOSE_SHORT\",\n");
        prompt.append("  \"confidence\": 0.0-1.0,\n");
        prompt.append("  \"leverage\": 1-20,\n");
        prompt.append("  \"position_size\": 0.0-1.0,\n");
        prompt.append("  \"close_ratio\": 0.0-1.0,\n");
        prompt.append("  \"reasoning\": \"必须按以下顺序详细说明：\\n" +
                "1) 📋 规则检查结果：\\n" +
                "   - 持仓方向一致性：[检查结果]\\n" +
                "   - 当前持仓盈亏百分比：[X%]\\\\n" +
                "2) 🎯 趋势分析（核心）：\\n" +
                "   - 主要趋势方向：[多头/空头/震荡]\\n" +
                "   - 趋势强度：[强/中/弱]\\n" +
                "   - 多周期趋势一致性：[高/中/低]\\n" +
                "   - 关键支撑或阻力位分析：[位置和强度]\\\\n" +
                "   - 趋势一致性：[分析k线数据、大行情分析、交易员观点的一致性]\\n" +
                "   - 价格共振强度：[支撑阻力重叠程度]\\n" +
                "   - 最佳交易机会：[共振信号最强的机会]\\\\n" +
                "3) 🧠 AI大行情自主解析：\\n" +
                "   - 趋势综合判断：[基于内容解析的趋势方向、强度、依据]\\n" +
                "   - 关键价格位置：[具体提取的支撑位和阻力位价格列表]\\n" +
                "   - 交易策略要点：[解析出的具体做多/做空建议和时机]\\n" +
                "   - 风险提示总结：[识别的主要风险因素和应对建议]\\\\n" +
                "4) 👥 交易员观点量化分析：\\n" +
                "   - 观点分布：[多头X人、空头X人、震荡X人的具体统计]\\n" +
                "   - 建仓区间分析：[交易员建议的具体建仓价格区间]\\n" +
                "   - 止损设置分析：[普遍止损位置和合理性评估]\\n" +
                "   - 共识强度：[市场共识程度评估]\\\\n" +
                "5) 🔍 价格共振识别：\\n" +
                "   - 多头支撑共振：[大行情支撑位与交易员多头建仓区间的重叠区域]\\n" +
                "   - 空头阻力共振：[大行情阻力位与交易员空头建仓区间的重叠区域]\\n" +
                "   - 多空平衡区域：[大行情支撑阻力与交易员建仓区间重叠度高的价格区间]\\n" +
                "   - 共振强度：[强/中/弱等级及依据]\\n" +
                "   - 最佳机会：[基于共振分析的最优先交易机会描述]\\\\n" +
                "6) 🔄 加仓机会评估：\\n" +
                "   - 当前持仓方向：[LONG/SHORT/NONE]\\n" +
                "   - 加仓条件满足度：[完全满足/部分满足/不满足]\\n" +
                "   - 加仓信号类型：[回调支撑加仓/反弹阻力加仓/趋势突破加仓]\\n" +
                "   - RSI加仓信号：[多头:20-30反弹/空头:70-80回落/无信号]\\n" +
                "   - 加仓风险收益比：[有利/一般/不利]\\\\n" +
                "7) 🔄 RSI背离分析：\\n" +
                "   - 顶背离检测：[有/无]，[信号强度]\\n" +
                "   - 底背离检测：[有/无]，[信号强度]\\n" +
                "   - 背离确认：[已确认/待确认/无效]\\n" +
                "   - 多周期背离协调性：[高/中/低]\\\\n" +
                "8) 📊 入场时机分析：\\n" +
                "   - 顺势信号确认：[是/否]\\n" +
                "   - 关键位置机会：[是否处于关键支撑或阻力位]\\n" +
                "   - 入场信号类型：[新开仓/加仓/平仓]\\n" +
                "   - 风险收益评估：[有利/一般/不利]\\\\n" +
                "9) 🔍 技术指标确认：\\n" +
                "   - RSI多周期状态：[各周期数值和加仓信号]\\n" +
                "   - MACD趋势确认：[各周期DIF/DEA状态]\\n" +
                "   - EMA排列分析：[短中长期均线关系]\\n" +
                "   - 布林带位置分析：[各周期位置和信号]\\\\n" +
                "10) ⚖️ 最终决策理由：\\n" +
                "   - 大行情共振依据：[关键共振信号]\\n" +
                "   - 技术确认依据：[主要技术信号]\\n" +
                "   - 趋势判断依据：[主要依据]\\n" +
                "   - RSI加仓信号：[20-30反弹/70-80回落的具体分析]\\n" +
                "   - RSI背离分析：[背离信号的重要性和可靠性]\\n" +
                "   - 关键位置分析：[支撑或阻力位有效性]\\n" +
                "   - 风险控制措施：[仓位、杠杆、止损安排]\\n" +
                "   - 预期收益目标：[短期和中期目标]\"\n");
        prompt.append("}\n\n");

        prompt.append("🚨 最后再次强调：只输出纯JSON格式，不要有任何其他内容！\n");
        return prompt.toString();
    }

    /**
     * 发送聊天请求
     */
    private String sendChatRequest(String prompt, String apiKey) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "deepseek-chat");
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 2048);
        requestBody.put("stream", false);

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        messages.add(message);
        requestBody.put("messages", messages);

        try {
            String requestBodyJson = objectMapper.writeValueAsString(requestBody);
            log.info("🔧 发送给DeepSeek的请求体: {}", requestBodyJson);

            String response = webClient.post().uri("/chat/completions").header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json").bodyValue(requestBody).retrieve().bodyToMono(String.class).timeout(Duration.ofMinutes(30)).block();

            log.info("✅ DeepSeek API响应: {}", response);
            return response;

        } catch (Exception e) {
            log.error("❌ DeepSeek API调用失败: {}", e.getMessage());
            throw new RuntimeException("DeepSeek API调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 💰 根据AI返回的杠杆、仓位比例、账户可用资金和当前价格，计算实际下单数量（高精度版）
     *
     * @param availableCash 账户可用资金（USD）
     * @param positionSize  仓位比例（0~1）
     * @param leverage      杠杆倍数
     * @param currentPrice  当前价格（USD）
     * @return 计算后下单数量（BTC）
     */
    private double calculateOrderQty(double availableCash, double positionSize, int leverage, double currentPrice, String action) {
        // ✅ 全程使用BigDecimal避免精度丢失
        BigDecimal cash = BigDecimal.valueOf(availableCash);         // 账户可用现金
        BigDecimal posSize = BigDecimal.valueOf(positionSize);       // 仓位比例（AI返回的0.02等）
        BigDecimal lev = BigDecimal.valueOf(leverage);               // 杠杆倍数
        BigDecimal price = BigDecimal.valueOf(currentPrice);         // 当前价格

        // 🧮 计算公式：下单数量 = (现金 × 仓位比例 × 杠杆) ÷ 当前价格
        BigDecimal numerator = cash.multiply(posSize).multiply(lev); // 分子 = 现金 × 仓位比例 × 杠杆
        BigDecimal orderQty = numerator.divide(price, 8, RoundingMode.HALF_UP); // 除法保留8位精度

        // 🔢 保留3位小数（比3位更安全，不会被四舍五入为0.0）
        orderQty = orderQty.setScale(3, RoundingMode.HALF_UP);

        // ⚠️ 如果动作是 HOLD，则直接返回 0，不做任何调整
        if ("HOLD".equalsIgnoreCase(action)) {
            log.info("🤖 动作为 HOLD，下单数量强制为 0.0000 BTC");
            return 0.0;
        }

        // ⚠️ 设置最小下单量（Bybit等交易所最小交易单位通常为0.001 BTC）
        BigDecimal minOrderQty = new BigDecimal("0.001");
        if (orderQty.compareTo(minOrderQty) < 0) {
            log.warn("⚠️ 计算结果过小: {} BTC，自动调整为最小下单量 0.001 BTC", orderQty);
            orderQty = minOrderQty;
        }

        // 🧾 日志打印详细过程
        log.info("📊 下单数量计算公式: ({} × {} × {}) ÷ {} = {} BTC",
                cash, posSize, lev, price, orderQty);

        // ✅ 返回double（仅用于API传输）
        return orderQty.doubleValue();
    }

    /**
     * 💼 根据AI返回的平仓比例，计算实际平仓数量
     *
     * @param currentPosition 当前持仓数量（BTC）
     * @param closeRatio      平仓比例（0~1）
     * @param action          AI返回的动作（CLOSE_LONG / CLOSE_SHORT / HOLD）
     * @return 实际平仓数量（BTC）
     */
    private double calculateCloseQty(double currentPosition, double closeRatio, String action) {
        // ⚠️ HOLD 或无持仓时直接返回0
        if ("HOLD".equalsIgnoreCase(action) || currentPosition <= 0 || closeRatio <= 0) {
            log.info("🤖 动作为 {} 或无有效持仓，平仓数量=0", action);
            return 0.0;
        }

        // ✅ 计算应平仓数量 = 当前仓位 × 平仓比例
        BigDecimal position = BigDecimal.valueOf(currentPosition);
        BigDecimal ratio = BigDecimal.valueOf(closeRatio);
        BigDecimal closeQty = position.multiply(ratio);

        // 🔢 保留3位小数（符合Bybit等最小交易单位）
        closeQty = closeQty.setScale(3, RoundingMode.HALF_UP);

        // ⚠️ 不能小于最小下单量
        BigDecimal minOrderQty = new BigDecimal("0.001");
        if (closeQty.compareTo(minOrderQty) < 0) {
            log.warn("⚠️ 平仓结果过小: {} BTC，自动调整为最小平仓量 0.001 BTC", closeQty);
            closeQty = minOrderQty;
        }

        log.info("📉 平仓数量计算公式: {} × {} = {} BTC", position, ratio, closeQty);

        return closeQty.doubleValue();
    }


    /**
     * 获取RSI信号描述
     */
    private String getRSISignalDescription(Double rsi) {
        if (rsi == null) return "数据不可用";
        if (rsi <= 21) return "🔴 严重超卖，强烈反弹信号";
        if (rsi <= 30) return "🟠 超卖区域，可能反弹";
        if (rsi <= 40) return "🟡 偏空区域，谨慎看空";
        if (rsi <= 60) return "🟢 中性区域，趋势跟随";
        if (rsi <= 71) return "🟠 超买区域，可能下跌";
        if (rsi <= 80) return "🔴 严重超买，强烈下跌信号";
        return "🔴 强烈超买信号，强烈下跌信号";
    }

    /**
     * 解析AI决策
     */
    private TradingDecision parseAIDecision(String response, MarketData md15m, PortfolioStatus portfolio) throws Exception {
        Map<String, Object> responseMap = objectMapper.readValue(response, Map.class); // 反序列化响应JSON
        List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices"); // 读取choices

        if (choices != null && !choices.isEmpty()) {                      // 校验choices存在
            Map<String, Object> choice = choices.get(0);                  // 取第一条choice
            Map<String, Object> message = (Map<String, Object>) choice.get("message"); // 获取message对象
            String content = (String) message.get("content");             // 获取message的content文本


            String jsonStr = extractJsonFromContent(content);             // 从content中提纯出JSON字符串
            Map<String, Object> decisionMap = objectMapper.readValue(jsonStr, Map.class); // 转成Map

            String action = (String) decisionMap.getOrDefault("action", "HOLD"); // 获取action，默认HOLD
            Double confidence = ((Number) decisionMap.getOrDefault("confidence", 0.5)).doubleValue(); // 置信度
            String reasoning = (String) decisionMap.getOrDefault("reasoning", "AI未提供详细分析"); // 理由
            Double positionSize = ((Number) decisionMap.getOrDefault("position_size", 0.1)).doubleValue(); // 仓位比例
            Double closeRatio = ((Number) decisionMap.getOrDefault("close_ratio", 0.0)).doubleValue(); // 平仓比例
            // === 新增：解析leverage并限幅 ===
            Integer leverage = ((Number) decisionMap.getOrDefault("leverage", 1)).intValue(); // 解析杠杆
            if (leverage < 1) leverage = 1;                          // 小于1纠正为1
            if (leverage > 20) leverage = 20;                        // 大于20纠正为20
            // === 动态计算下单数量 ===
            Double orderQty;
            if (action.startsWith("CLOSE")) {
                orderQty = calculateCloseQty(portfolio.getPosition(), closeRatio, action);
            } else {
                orderQty = calculateOrderQty(portfolio.getCash(), positionSize, leverage, md15m.getCurrentPrice(), action);
            }
            // 组装TradingDecision
            TradingDecision decision = new TradingDecision(action, confidence, reasoning, positionSize, LocalDateTime.now()); // 构造
            decision.setLeverage(leverage);                           // 设置AI建议杠杆
            decision.setOrderQty(orderQty);                           // 设置AI建议的直接下单数量
            return decision;
        }
        throw new RuntimeException("无效的API响应格式");
    }


    /**
     * 提取JSON内容
     */
    private String extractJsonFromContent(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}') + 1;
        if (start >= 0 && end > start) {
            return content.substring(start, end);
        }
        throw new RuntimeException("未找到有效的JSON响应");
    }

    /**
     * 获取备用决策（基于新的RSI策略）
     */
    private TradingDecision getFallbackDecision(MarketData data) {
        String action = "HOLD";                // 默认观望
        Double confidence = 0.3;               // 置信度较低
        String reasoning = "备用策略: 技术指标不明确"; // 理由
        Double positionSize = 0.05;            // 小仓位
        int leverage = 1;                      // 默认低杠杆
        Double orderQty = 0.002;               // 默认小数量


        if (data != null && data.getRsi() != null) { // 若有RSI信息
            double rsi = data.getRsi();               // 读取RSI
            if (rsi <= 21) {                          // RSI强买区
                action = "BUY";                       // 建议买入
                confidence = 0.85;                    // 置信度高
                reasoning = "备用策略: RSI≤21强买入信号"; // 理由
                positionSize = 0.25;                  // 较大仓位
                leverage = 12;                        // 较高杠杆（但不是20）
                orderQty = 0.015;                     // 较大数量
            } else if (rsi <= 26) {                   // RSI弱买区
                action = "BUY";                       // 建议买入
                confidence = 0.65;                    // 中等置信度
                reasoning = "备用策略: RSI≤26弱买入信号"; // 理由
                positionSize = 0.15;                  // 中等仓位
                leverage = 8;                         // 中等杠杆
                orderQty = 0.008;                     // 中等数量
            } else if (rsi >= 74) {                   // RSI强卖区
                action = "SELL";                      // 建议卖出
                confidence = 0.85;                    // 置信度高
                reasoning = "备用策略: RSI≥74强卖出信号"; // 理由
                positionSize = 0.25;                  // 较大仓位
                leverage = 12;                        // 较高杠杆
                orderQty = 0.015;                     // 较大数量
            } else if (rsi >= 72) {                   // RSI弱卖区
                action = "SELL";                      // 建议卖出
                confidence = 0.65;                    // 中等置信度
                reasoning = "备用策略: RSI≥72弱卖出信号"; // 理由
                positionSize = 0.15;                  // 中等仓位
                leverage = 8;                         // 中等杠杆
                orderQty = 0.008;                     // 中等数量
            }
        }

        TradingDecision td = new TradingDecision(action, confidence, reasoning, positionSize, LocalDateTime.now()); // 构造
        td.setLeverage(leverage);                     // 设置备用杠杆
        td.setOrderQty(orderQty);                     // 设置备用下单数量
        return td;                                    // 返回
    }
    // ========== 辅助方法 ==========


    /**
     * 获取MACD状态描述
     */
    private String getMACDStatus(double dif, double dea) { // MACD状态方法

        boolean isGoldCross = dif > dea && (dif - dea) > 0.001;
        boolean isDeadCross = dif < dea && (dea - dif) > 0.001;

        if (isGoldCross && dif > 0) return "🟢 零轴上金叉，强烈多头";
        if (isGoldCross && dif <= 0) return "🟡 零轴下金叉，弱势反弹";
        if (isDeadCross && dif >= 0) return "🟠 零轴上死叉，强势回调";
        if (isDeadCross && dif < 0) return "🔴 零轴下死叉，强烈空头";

        if (dif > 0 && dea > 0) return "🟢 零轴上运行，多头趋势";
        return "🔴 零轴下运行，空头趋势";
    }


    /**
     * 🚀 推送「当日大行情 + 交易员观点」到钉钉
     *
     * @param symbol 币种
     */
    public void pushMarketAndTraderSummary(String symbol) {
        try {
            // 1️⃣ 构建 Markdown 文本
            String markdown = buildMarketAndTraderSummary(symbol);

            // 2️⃣ 判空校验
            if (markdown == null || markdown.trim().isEmpty()) {
                log.warn("⚠️ 无法生成当日市场与交易员摘要。");
                return;
            }

            // 3️⃣ 控制台输出（方便调试）
            log.info("\n{}", markdown);

            // 4️⃣ 发送钉钉 Markdown 消息
            DingDingMessageUtil.sendMarkdown("📊 当日市场与交易员摘要", markdown);

        } catch (Exception e) {
            // 5️⃣ 错误日志
            log.error("❌ 生成市场摘要或推送钉钉失败: {}", e.getMessage());
        }
    }

    /**
     * 🧩 构建美观的 Markdown 文本
     */
    private String buildMarketAndTraderSummary(String symbol) {
        // ✳️ 初始化 Markdown 构建器
        SimpleMarkdownBuilder md = SimpleMarkdownBuilder.create();

        // =============== 🌐 大行情分析区域 ==================
        md.title("🌐 当日大行情分析", 3);                       // 标题（H3）
        md.text("━━━━━━━━━━━━━━━━━━━━━━━", true);               // 分隔线

        // 查询数据库中的市场分析记录
        List<MarketOverviewEntity> overviews = marketOverviewRepository.findLatestRecordOfEachAuthor(); // 不按时间，只取每作者最新

        // 若当天无分析数据
        if (overviews == null || overviews.isEmpty()) {
            md.text("> 暂无当日大行情分析记录。", true);
        } else {
            // 遍历并输出每条分析记录
            for (MarketOverviewEntity o : overviews) {
                md.text("👤 作者：**" + o.getAuthor() + "**", true);
                md.text("🕓 时间：**" + o.getCreatedAt().format(FORMATTER) + "**", true);

                if (o.getFullAnalysis() != null && !o.getFullAnalysis().isBlank()) {
                    md.text("📝 分析内容：", true);
                    md.text(o.getFullAnalysis().trim(), true);
                }

                // 单条分析间的分割线
                md.text("━━━━━━━━━━━━━━━━━━━━━━━", true);
            }
        }

        // =============== 👥 当日交易员观点 ==================
        md.nextLine();                                          // 空行
        md.title("👥 当日交易员观点", 3);                       // 标题
        md.text("━━━━━━━━━━━━━━━━━━━━━━━", true);               // 分隔线

        // 查询当天策略记录
        List<TraderStrategyEntity> todayStrategies = traderStrategyService.getTodayStrategiesBySymbol(symbol);

        // 无策略时提示
        if (todayStrategies == null || todayStrategies.isEmpty()) {
            md.text("> 暂无当日交易员策略记录。", true);
        } else {
            // 统计方向数量
            long bull = todayStrategies.stream().filter(s -> "多".equalsIgnoreCase(s.getDirection()) || "LONG".equalsIgnoreCase(s.getDirection())).count();
            long bear = todayStrategies.stream().filter(s -> "空".equalsIgnoreCase(s.getDirection()) || "SHORT".equalsIgnoreCase(s.getDirection())).count();
            long neutral = todayStrategies.stream().filter(s -> "震荡".equalsIgnoreCase(s.getDirection()) || "NEUTRAL".equalsIgnoreCase(s.getDirection())).count();

            // 遍历交易员策略
            for (TraderStrategyEntity s : todayStrategies) {
                String comment = s.getComment() == null ? "" : s.getComment().trim();
                if (comment.length() > 160) comment = comment.substring(0, 160) + "...";

                // 拼接单条策略文本
                StringBuilder line = new StringBuilder();
                line.append("• **").append(s.getTraderName()).append("** ").append("→ 方向：").append(s.getDirection()).append("，建仓区间：").append(s.getEntryRange());

                if (s.getStopLoss() != null && !s.getStopLoss().isBlank())
                    line.append("，止损：").append(s.getStopLoss());
                if (s.getTakeProfit() != null && !s.getTakeProfit().isBlank())
                    line.append("，止盈：").append(s.getTakeProfit());
                if (!comment.isEmpty()) line.append("。💬 备注：").append(comment);

                md.text(line.toString(), true);
            }

            // 共识总结
            md.nextLine();
            md.text("> **共识简评：** 多头=" + bull + "，空头=" + bear + "，震荡=" + neutral + "。", true);

            // 生成结论提示
            if (bear > bull && bear >= 2) {
                md.text("⚠️ **市场偏空** → 建议信号不一致时降低仓位。", true);
            } else if (bull > bear && bull >= 2) {
                md.text("🚀 **市场偏多** → 建议冲高回落风险下控制杠杆。", true);
            } else {
                md.text("💡 **市场分歧较大** → 建议谨慎，以技术信号为主。", true);
            }

            md.text("━━━━━━━━━━━━━━━━━━━━━━━", true);
        }

        // =============== ⏰ 更新时间 ==================
        md.nextLine();
        md.text("🕒 更新时间：**" + LocalDateTime.now().format(FORMATTER) + "**", true);

        // 返回最终 Markdown 字符串
        return md.build();
    }

    /**
     * 计算持仓盈亏百分比（基于价格变动）
     * 用于止盈止损判断，反映真实的市场波动
     *
     * @param portfolio    投资组合状态
     * @param currentPrice 当前价格
     * @param entryPrice   开仓均价
     * @return 持仓盈亏百分比
     */
    private double calculatePositionPnLPercent(PortfolioStatus portfolio, double currentPrice, double entryPrice) {
        if (entryPrice <= 0 || portfolio.getDirection() == null) {
            return 0.0;  // 无持仓或无效数据返回0
        }

        String direction = portfolio.getDirection().toUpperCase();  // 统一转为大写
        if ("LONG".equals(direction) || "多头".equalsIgnoreCase(portfolio.getDirection())) {
            // 多头持仓：(当前价 - 开仓价) / 开仓价 × 100%
            return (currentPrice - entryPrice) / entryPrice * 100;
        } else if ("SHORT".equals(direction) || "空头".equalsIgnoreCase(portfolio.getDirection())) {
            // 空头持仓：(开仓价 - 当前价) / 开仓价 × 100%
            return (entryPrice - currentPrice) / entryPrice * 100;
        }
        return 0.0;  // 未知方向返回0
    }

    /**
     * 计算过去24小时价格变化百分比（基于数据库K线）
     *
     * @param symbol 交易对
     * @return 过去24小时涨跌百分比，若无数据返回 null
     */
    private Double calculatePriceChange24h(String symbol) {
        try {
            // 1️⃣ 当前时间与24小时前
            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusHours(24);

            // 2️⃣ 查询过去24小时的所有15分钟K线
            List<MarketKlineEntity> klines = marketKlineRepository
                    .findBySymbolAndOpenTimeBetweenOrderByOpenTimeAsc(symbol, startTime, endTime);

            if (klines == null || klines.isEmpty()) {
                log.warn("⚠️ 计算24小时涨跌幅失败：无K线数据 {}", symbol);
                return null;
            }

            // 3️⃣ 获取最早一根K线的开盘价（24小时前）
            double openPrice = klines.get(0).getOpen();

            // 4️⃣ 获取最新一根K线的收盘价（当前）
            double closePrice = klines.get(klines.size() - 1).getClose();

            // 5️⃣ 计算涨跌百分比
            double changePercent = ((closePrice - openPrice) / openPrice) * 100.0;

            log.info("📊 {} 24小时价格变化: open={} → close={} → {:.2f}%", symbol, openPrice, closePrice, changePercent);
            return changePercent;

        } catch (Exception e) {
            log.error("❌ 计算24小时涨跌幅异常: {}", e.getMessage());
            return null;
        }
    }
}