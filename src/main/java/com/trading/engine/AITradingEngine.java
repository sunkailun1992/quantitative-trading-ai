package com.trading.engine;

// 导入必要的包

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.entity.MarketKlineEntity;
import com.trading.entity.StrategyLogEntity;
import com.trading.entity.TradeOrderEntity;
import com.trading.model.MarketData;
import com.trading.model.PortfolioStatus;
import com.trading.model.TradingDecision;
import com.trading.repository.MarketKlineRepository;
import com.trading.repository.StrategyLogRepository;
import com.trading.repository.TradeOrderRepository;
import com.trading.service.*;
import com.trading.util.DingDingMessageUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI量化交易引擎 - 完整实现DeepSeek冠军策略
 * 集成RSI策略、趋势确认、持仓时间管理和风险控制
 */
@Slf4j // Lombok：自动生成日志对象
@Component // Spring：声明为组件
@RequiredArgsConstructor // Lombok：自动生成构造函数
public class AITradingEngine {

    // ==============================
    // ✅ 依赖注入区
    // ==============================
    private final DeepSeekService deepSeekService;
    private final BybitTradingService bybitTradingService;
    private final RiskManagementService riskManagementService;
    private final StrategyLogRepository strategyLogRepository;
    private final TradeOrderRepository tradeOrderRepository;
    // ✅ 新增注入数据库限频服务
    private final TradeFrequencyLimitService tradeFrequencyLimitService;
    private final MarketKlineRepository marketKlineRepository;
    // ==============================
    // ⚙️ 交易状态变量
    // ==============================
    private double totalCapital = 1000.0;
    private double currentPosition = 0.0;
    private final AtomicInteger tradeCount = new AtomicInteger(0);
    private boolean tradingEnabled = true;

    private double portfolioPeakValue = 0.0;

    /**
     * 🧠 处理多周期市场数据（增强版）
     * 支持 15m / 1h / 1d / 1w 四周期联合分析。
     * 每个 MarketData 都会独立进行 AI 决策，内部融合短中长期信号。
     */
    @Async("tradingTaskExecutor") // 使用异步线程池执行交易决策
    public void processMarketData(List<MarketData> marketDataList) {
        // 1️⃣ 安全检查 - 若交易未启用则跳过
        if (!tradingEnabled) {
            log.debug("🚫 交易未启用，跳过市场数据处理");
            return;
        }

        try { // 2️⃣ 检查输入有效性
            if (marketDataList == null || marketDataList.isEmpty()) {
                log.warn("⚠️ 接收到的多周期市场数据为空");
                return;
            }

            // 6️⃣ 获取账户状态（资金、仓位、盈亏等）
            PortfolioStatus portfolio = bybitTradingService.getEnhancedPortfolioStatus();
            if (portfolio == null) {
                log.warn("⚠️ 无法获取真实账户状态，跳过执行");
                return;
            }

            // 7️⃣ 更新本地账户快照
            updateLocalPortfolioState(portfolio);

            // 🟢 1️⃣ 提取四个周期对象（AI 需要多周期综合判断）
            MarketData md15m = marketDataList.stream().filter(md -> "15m".equalsIgnoreCase(md.getPeriod())).findFirst().orElse(null);
            MarketData md1h = marketDataList.stream().filter(md -> "1h".equalsIgnoreCase(md.getPeriod())).findFirst().orElse(null);
            MarketData md1d = marketDataList.stream().filter(md -> "1d".equalsIgnoreCase(md.getPeriod())).findFirst().orElse(null);
            MarketData md1w = marketDataList.stream().filter(md -> "1w".equalsIgnoreCase(md.getPeriod())).findFirst().orElse(null);

            if (md15m == null) {
                log.warn("⚠️ 未找到15分钟周期数据，无法执行交易");
                return;
            }

            // ✅ 提取 symbol 和 price
            String symbol = md15m.getSymbol(); // 当前交易对
            double price = md15m.getCurrentPrice(); // 当前实时价格

            // 9️⃣ 调用 DeepSeek AI 生成交易决策（参考多周期）
            TradingDecision decision = deepSeekService.getTradingDecision(md15m, md1h, md1d, md1w, portfolio);

            // 日志打印AI决策内容
            log.info("🎯 AI 决策信号: {} | 置信度: {} | 理由: {}",
                    decision.getAction(),
                    String.format("%.2f", decision.getConfidence()),
                    decision.getReasoning());

            // 钉钉消息推送
            logTradingActivityDingDing(md15m, portfolio, decision);
            //Discord消息推送
            logTradingActivityDiscord(md15m, portfolio, decision);

            // 🔟 若AI给出“观望”信号，则直接退出
            if (!decision.shouldExecute()) {
                log.info("⚪ AI决策为HOLD（观望），不执行交易");
                return;
            }

            // 1️⃣1️⃣ 执行AI交易决策（含风控检查与杠杆调整）
            boolean executed = executeTradingDecisionWithTracking(decision, price, portfolio, md15m);

            // 1️⃣2️⃣ 若交易执行成功，则计入交易频率
            if (executed) {
                tradeFrequencyLimitService.incrementTradeCount(symbol);
                log.info("✅ 本次交易已执行并计入频率统计 [{}]", symbol);
            } else {
                log.info("🚫 AI信号触发，但交易未执行（风控/价格偏离）");
            }
            // 1️⃣3️⃣ 记录交易日志，用于回测与复盘
            logTradingActivity(md15m, portfolio, decision);

            // 1️⃣4️⃣ 写入策略执行成功日志（系统监控用途）
            strategyLogRepository.save(
                    new StrategyLogEntity(
                            null,
                            "INFO",
                            "成功执行AI交易流程: " + decision.getAction() + " @ " + symbol,
                            LocalDateTime.now()
                    )
            );

        } catch (Exception e) {
            // 捕获所有异常，防止线程池任务崩溃
            log.error("❌ 处理市场数据时发生异常: {}", e.getMessage(), e);
            strategyLogRepository.save(
                    new StrategyLogEntity(null, "ERROR",
                            "处理市场数据异常: " + e.getMessage(),
                            LocalDateTime.now())
            );
        }
    }

    /**
     * ⚙️ 执行AI交易决策，并返回是否成功执行
     * 仅当真实下单成功时返回true（用于统计交易频率）
     */
    private boolean executeTradingDecisionWithTracking(TradingDecision decision, double currentPrice, PortfolioStatus portfolio, MarketData md15m) {
        try {
            // 调用已有的 executeTradingDecision 执行下单
            double beforePosition = currentPosition; // 记录交易前持仓
            double beforeCapital = totalCapital; // 记录交易前资金

            // 调用原有执行逻辑
            executeTradingDecision(decision, currentPrice, portfolio, md15m);

            // 判断是否真的发生了持仓变化或资金变化
            boolean positionChanged = currentPosition != beforePosition;
            boolean capitalChanged = totalCapital != beforeCapital;

            // 仅当发生真实交易（买入或卖出成功）时返回true
            return positionChanged || capitalChanged;

        } catch (Exception e) {
            log.error("❌ 执行AI交易决策时出错: {}", e.getMessage());
            return false; // 出现异常视为未执行交易
        }
    }

    /**
     * 更新本地投资组合状态记录
     */
    private void updateLocalPortfolioState(PortfolioStatus portfolio) {
        if (portfolio != null) { // 检查投资组合是否有效
            // 更新本地记录以保持一致性
            this.totalCapital = portfolio.getTotalValue(); // 更新总资金
            this.currentPosition = portfolio.getPosition(); // 更新持仓

            // 更新峰值价值
            if (portfolio.getTotalValue() > portfolioPeakValue) { // 检查是否创新高
                portfolioPeakValue = portfolio.getTotalValue(); // 更新峰值
                log.info("📈 更新投资组合峰值: ${}", String.format("%.2f", portfolioPeakValue)); // 记录信息日志
            }

            log.debug("📊 更新本地投资组合状态 - 总资产: ${}, 持仓: {}, 峰值: ${}",
                    String.format("%.2f", totalCapital),
                    String.format("%.4f", currentPosition),
                    String.format("%.2f", portfolioPeakValue)); // 记录调试日志
        }
    }


    /**
     * 执行交易决策 - 集成RSI策略检查
     */
    private void executeTradingDecision(TradingDecision decision, double currentPrice, PortfolioStatus portfolio, MarketData md15m) {
        if (!decision.shouldExecute()) {                                       // 若AI建议HOLD则不执行
            return;                                                            // 返回
        }

        try {
            // === 1️⃣ 获取账户实时数据 ===
            double totalCapital = portfolio.getTotalValue();  // 获取账户总资产
            double cash = portfolio.getCash();                // 获取账户可用现金
            double currentPosition = portfolio.getPosition(); // 获取当前持仓数量

            // === 2️⃣ 优先使用AI直接提供的下单数量 ===
            double quantity;
            if (decision.getOrderQty() != null && decision.getOrderQty() > 0) {
                // 使用AI直接提供的下单数量
                quantity = decision.getOrderQty();
                log.info("🤖 使用AI直接建议的下单数量: {}", String.format("%.4f", quantity));
            } else {
                // 回退到仓位比例计算（兼容旧逻辑）
                double positionSize = decision.getPositionSize();  // 获取AI建议的仓位比例
                quantity = positionSize * portfolio.getTotalValue() / currentPrice; // 按照当前价格计算BTC数量
                log.info("📊 仓位计算: AI建议仓位={}%, 计算数量={}",
                        String.format("%.2f", positionSize * 100), String.format("%.4f", quantity));
            }

            // === 3️⃣ 获取AI提供的杠杆 ===
            int leverageToUse = 1; // 默认使用1倍杠杆
            if (decision.getLeverage() != null && decision.getLeverage() > 0) {   // 如果AI提供了杠杆信息
                leverageToUse = decision.getLeverage();  // 使用AI给出的杠杆值
                log.info("🤖 使用AI提供的杠杆: {}x", leverageToUse);  // 打印使用的杠杆值
            } else {
                // 如果AI没有给出杠杆，使用系统规则计算杠杆
                leverageToUse = 10;
            }

            // === 限制最大杠杆为20倍 ===
            if (leverageToUse > 20) { // 防止杠杆过高
                log.warn("⚠️ 杠杆超出上限({}x)，自动限制为20x", leverageToUse);
                leverageToUse = 20;  // 限制杠杆为最大值20倍
            } else if (leverageToUse < 1) {
                leverageToUse = 1;  // 若杠杆小于1，则强制使用1倍杠杆
            }

            // === 4️⃣ 设置杠杆 ===
            boolean setSuccess = bybitTradingService.setLeverage("BTCUSDT", leverageToUse);  // 设置实际杠杆
            if (!setSuccess) {
                log.error("❌ 设置杠杆失败，回退为默认10x");  // 如果设置失败，使用默认10倍杠杆
            }
            // === 5️⃣ 校验风险与仓位限制 ===
            if (!riskManagementService.validateOrder("BTCUSDT", decision.getAction(), quantity, totalCapital, currentPosition)) {
                log.warn("🚫 风控拒绝执行订单");  // 如果风控拒绝执行，直接返回
                return;
            }

            // 检查资金是否足够买入
            double availableBuyingPower = cash * leverageToUse;  // 杠杆资金 = 自有资金 × 杠杆倍数
            if ("BUY".equals(decision.getAction()) && quantity * currentPrice > availableBuyingPower) {
                // 若预期买入金额超过杠杆后的可用购买力，则资金不足
                log.warn("❌ 资金不足，所需资金为${}，可用资金(含杠杆)为${}", quantity * currentPrice, availableBuyingPower);
                return;  // 资金不足，停止执行下单
            }

            // === 7️⃣ 执行市场下单 ===
            JsonNode result;
            String action = decision.getAction();  // 获取AI的动作信号
            if (action.equals("BUY") || action.equals("SELL")) {
                // 统一处理开仓
                result = bybitTradingService.placeMarketOrder(portfolio.getSymbol(), action, quantity, leverageToUse, portfolio, md15m, decision);
            } else if (action.equals("CLOSE_LONG") || action.equals("CLOSE_SHORT")) {
                // 统一处理平仓
                result = bybitTradingService.closePositionMarket(portfolio.getSymbol(), action, quantity, leverageToUse, portfolio, md15m, decision);
            } else {
                // HOLD
                result = new ObjectMapper().createObjectNode()
                        .put("retCode", 0)
                        .put("retMsg", "HOLD - No action performed");
            }

            // === 8️⃣ 检查下单结果 ===
            if (result.has("retCode") && result.get("retCode").asInt() == 0) {  // 如果下单成功
                log.info("✅ 下单成功: {} {}x, 数量={}", decision.getAction(), leverageToUse, quantity);
            } else {  // 如果下单失败
                String errorMsg = result.has("retMsg") ? result.get("retMsg").asText() : "未知错误";
                log.error("❌ 下单失败: {}", errorMsg);  // 打印错误信息
            }

            tradeCount.incrementAndGet();                                       // 累计交易次数（成功与否都+1可视需求调整）

        } catch (Exception e) { // 捕获异常
            log.error("订单执行失败: {}", e.getMessage(), e); // 记录错误日志
        }
    }

    /**
     * 🧾 记录交易活动日志 - 展示AI决策与市场状态
     *
     * @param data      当前市场数据（MarketData）
     * @param portfolio 当前账户状态（PortfolioStatus）
     * @param decision  当前AI交易决策（TradingDecision）
     */
    public void logTradingActivity(MarketData data, PortfolioStatus portfolio, TradingDecision decision) {

        log.info("\n" + "═".repeat(100));  // 分隔线
        log.info("🤖【DeepSeek AI 交易决策报告】");
        log.info("═".repeat(100));

        // === 🕒 时间与市场信息 ===
        log.info("⏰ 时间戳: {}", LocalDateTime.now()); // 当前日志时间
        log.info("💱 交易对: {}", data.getSymbol());   // 如 BTCUSDT
        log.info("🧭 周期: {}", data.getPeriod());     // 如 15m / 1h / 1d / 1w
        log.info("💰 当前价格: ${}", String.format("%.2f", data.getCurrentPrice())); // 当前市价
        // === 计算并输出 24小时真实涨跌幅 ===
        Double realChange24h = calculatePriceChange24h(data.getSymbol());
        if (realChange24h != null) {
            log.info("📊 过去24小时价格变化: {}%", String.format("%.2f", realChange24h)); // 涨跌幅
        }
        if (data.getVolume() != null) {
            log.info("📦 成交量: {}", String.format("%.2f", data.getVolume())); // 成交量
        }

        // === 📈 技术指标区域 ===
        log.info("📘 技术指标分析：");
        if (data.getRsi() != null)
            log.info("   RSI(14): {}", String.format("%.2f", data.getRsi())); // RSI相对强弱
        if (data.getMacdDif() != null && data.getMacdDea() != null)
            log.info("   MACD: DIF={:.4f}, DEA={:.4f}, HIST={:.4f}", data.getMacdDif(), data.getMacdDea(), data.getMacdHistogram());
        if (data.getBbPosition() != null)
            log.info("   布林带位置: {}%", String.format("%.1f", data.getBbPosition()));
        if (data.getBbBandwidth() != null)
            log.info("   布林带带宽: {}%", String.format("%.1f", data.getBbBandwidth()));
        if (data.getEma20() != null && data.getEma50() != null)
            log.info("   EMA趋势: EMA20={}, EMA50={} → {}",
                    String.format("%.2f", data.getEma20()),
                    String.format("%.2f", data.getEma50()),
                    data.getEma20() > data.getEma50() ? "📈 上升趋势" : "📉 下降趋势");
        if (data.getAtr14() != null)
            log.info("   波动率: ATR(3)={}, ATR(14)={}",
                    String.format("%.3f", data.getAtr3()), String.format("%.3f", data.getAtr14()));

        // === 💡 AI 决策输出 ===
        log.info("\n🤖 AI 交易决策：");
        log.info("🎯 行动建议: {}", decision.getAction());
        log.info("📈 建议杠杆: {}x", decision.getLeverage());
        log.info("📊 建议仓位比例: {}%", String.format("%.1f", decision.getPositionSize() * 100));
        log.info("💰 下单数量: {}", decision.getOrderQty());
        log.info("💪 决策置信度: {}%", String.format("%.1f", decision.getConfidence() * 100));
        log.info("🧠 决策逻辑: {}", decision.getReasoning());

        // 获取当前价格和开仓价格
        double currentPrice = data.getCurrentPrice();  // 当前市场价格
        double entryPrice = portfolio.getEntryPrice() != null ? portfolio.getEntryPrice() : currentPrice;  // 开仓均价，若无则用当前价
        // === 💼 账户状态（增强版） ===
        log.info("💼 当前账户状态：");
        log.info("   💵 总资产: ${}", String.format("%.2f", portfolio.getTotalValue() != null ? portfolio.getTotalValue() : 0.0));
        log.info("   💰 可用现金: ${}", String.format("%.2f", portfolio.getCash() != null ? portfolio.getCash() : 0.0));
        log.info("   📊 持仓数量: {} {}", String.format("%.4f", portfolio.getPosition() != null ? portfolio.getPosition() : 0.0), portfolio.getSymbol());
        log.info("   🧭 持仓方向: {}", portfolio.getDirection() != null ? portfolio.getDirection() : "NONE");
        log.info("   🎯 开仓均价: ${}", String.format("%.2f", portfolio.getEntryPrice() != null ? portfolio.getEntryPrice() : 0.0));
        log.info("   💹 标记价格: ${}", String.format("%.2f", portfolio.getMarkPrice() != null ? portfolio.getMarkPrice() : 0.0));
        log.info("   🧮 占用保证金: ${}", String.format("%.2f", portfolio.getMarginUsed() != null ? portfolio.getMarginUsed() : 0.0));
        log.info("   ⚠️ 强平价格: ${}", String.format("%.2f", portfolio.getLiquidationPrice() != null ? portfolio.getLiquidationPrice() : 0.0));
        // 盈亏区块
        double positionPnLPercent = calculatePositionPnLPercent(portfolio, currentPrice, entryPrice);
        String positionPnlEmoji = positionPnLPercent >= 0 ? "🟢 盈利" : "🔴 亏损";
        log.info("   {} 持仓盈亏: {}%", positionPnlEmoji, String.format("%.2f", Math.abs(positionPnLPercent))); // 新增持仓盈亏百分比
        double unrealisedPnL = portfolio.getUnrealisedPnL() != null ? portfolio.getUnrealisedPnL() : 0.0;
        String pnlSign = unrealisedPnL >= 0 ? "🟢 盈利" : "🔴 亏损";
        log.info("   {} 未实现盈亏: ${}", pnlSign, String.format("%.2f", Math.abs(unrealisedPnL)));
        double pnlPercent = portfolio.getPnLPercent() != null ? portfolio.getPnLPercent() : 0.0;
        String roiSign = pnlPercent >= 0 ? "🟢" : "🔴";
        log.info("   {} 保证金收益率: {:.2f}%", roiSign, pnlPercent);

        // === 📚 统计信息 ===
        log.info("\n📊 交易统计：");
        log.info("   🔢 执行次数: {}", tradeCount.get());
        log.info("   🕰️ 最新决策时间: {}", decision.getDecisionTime());
        log.info("   🧾 策略记录ID: {}", decision.getStrategyRecordId() != null ? decision.getStrategyRecordId() : "无");

        log.info("═".repeat(100) + "\n"); // 结束分隔线
    }

    /**
     * 🧠 DeepSeek AI 交易日志推送（美化版）
     * - 控制台打印 + 同步钉钉 Markdown 消息
     * - 统一视觉风格：标题 + 分区 + Emoji + 分隔线
     */
    private void logTradingActivityDingDing(MarketData data, PortfolioStatus portfolio, TradingDecision decision) {

        // 📅 格式化时间输出
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        // 🧱 使用 StringBuilder 构建 Markdown 文本
        StringBuilder md = new StringBuilder();

        // =============== 🧩 报告标题 ===================
        md.append("## 🤖 DeepSeek AI 交易决策报告\n");
        md.append("━━━━━━━━━━━━━━━━━━━━━━━\n\n");

        // =============== ⏰ 基本行情信息 ===================
        md.append("### ⏰ 基本信息\n");
        md.append("💱 **交易对：** ").append(data.getSymbol()).append("  \n");
        md.append("🧭 **周期：** ").append(data.getPeriod()).append("  \n");
        md.append("💰 **当前价格：** $").append(String.format("%.2f", data.getCurrentPrice())).append("  \n");

        // === 计算并输出 24小时真实涨跌幅 ===
        Double realChange24h = calculatePriceChange24h(data.getSymbol());
        if (realChange24h != null)
            md.append("📊 **过去24小时价格变化：** ").append(String.format("%.2f", realChange24h)).append("%  \n");
        if (data.getVolume() != null)
            md.append("📦 **成交量：** ").append(String.format("%.2f", data.getVolume())).append("\n");

        md.append("━━━━━━━━━━━━━━━━━━━━━━━\n\n");

        // =============== 📈 技术指标 ===================
        md.append("### 📈 技术指标分析\n");

        // RSI
        md.append("RSI(14)：**").append(String.format("%.2f", data.getRsi() != null ? data.getRsi() : 0.0)).append("**  ");

        // MACD 三要素
        if (data.getMacdDif() != null && data.getMacdDea() != null) {
            md.append("MACD：DIF=").append(String.format("%.4f", data.getMacdDif()))
                    .append("，DEA=").append(String.format("%.4f", data.getMacdDea()))
                    .append("，HIST=").append(String.format("%.4f", data.getMacdHistogram())).append("  ");
        }

        // EMA 趋势
        md.append("\n\n### 📉 EMA 均线趋势分析\n"); // 新增分区标题

        // 输出所有 EMA 数值（20、50、144、168、288、338）
        md.append(String.format("🔹 **EMA20：** %.2f  \n", data.getEma20() != null ? data.getEma20() : 0.0));  // EMA20
        md.append(String.format("🔹 **EMA50：** %.2f  \n", data.getEma50() != null ? data.getEma50() : 0.0));  // EMA50
        md.append(String.format("🔹 **EMA144：** %.2f  \n", data.getEma144() != null ? data.getEma144() : 0.0)); // EMA144
        md.append(String.format("🔹 **EMA168：** %.2f  \n", data.getEma168() != null ? data.getEma168() : 0.0)); // EMA168
        md.append(String.format("🔹 **EMA288：** %.2f  \n", data.getEma288() != null ? data.getEma288() : 0.0)); // EMA288
        md.append(String.format("🔹 **EMA338：** %.2f  \n", data.getEma338() != null ? data.getEma338() : 0.0)); // EMA388

        // 判断短期趋势（EMA20 vs EMA50）
        if (data.getEma20() != null && data.getEma50() != null) { // 若短中期EMA存在
            boolean upTrend = data.getEma20() > data.getEma50(); // 比较短中期趋势方向
            md.append("📈 **短中期趋势：** ").append(upTrend ? "上升趋势 📈" : "下降趋势 📉").append("  \n"); // 输出趋势结论
        }

        // 判断多周期排列（EMA20 < EMA50 < EMA144 < EMA288）
        if (data.getEma20() != null && data.getEma50() != null && data.getEma144() != null && data.getEma288() != null) {
            boolean bullTrend = data.getEma20() > data.getEma50() && data.getEma50() > data.getEma144() && data.getEma144() > data.getEma288(); // 多头排列
            boolean bearTrend = data.getEma20() < data.getEma50() && data.getEma50() < data.getEma144() && data.getEma144() < data.getEma288(); // 空头排列

            if (bullTrend) md.append("🟢 **均线排列结构：** 多头排列，趋势强劲上行 🚀  \n"); // 输出多头趋势
            else if (bearTrend) md.append("🔴 **均线排列结构：** 空头排列，趋势明显下行 ⚠️  \n"); // 输出空头趋势
            else md.append("⚪ **均线排列结构：** 混合排列，可能处于震荡区间 ⚖️  \n"); // 输出震荡结构
        }

        // ---------------- ATR 波动性 ----------------
        md.append("\n📊 **波动性（ATR）：** "); // 输出ATR标题
        md.append(String.format("ATR(3)=%.3f, ATR(14)=%.3f  \n",
                data.getAtr3() != null ? data.getAtr3() : 0.0,
                data.getAtr14() != null ? data.getAtr14() : 0.0)); // 输出ATR值

        md.append("━━━━━━━━━━━━━━━━━━━━━━━\n\n"); // 分隔线

        // =============== 🎯 决策结果 ===================
        md.append("### 🎯 AI 决策结果\n");

        md.append("🤖 **操作建议：** ").append(decision.getAction()).append("  \n");
        md.append("💪 **置信度：** ").append(String.format("%.1f", decision.getConfidence() * 100)).append("%  \n");
        md.append("💰 **下单量：** ").append(String.format("%.4f", decision.getOrderQty())).append("  \n");
        md.append("📊 **建议仓位：** ").append(String.format("%.1f", decision.getPositionSize() * 100)).append("%  \n");
        md.append("⚙️ **杠杆建议：** ").append(decision.getLeverage()).append("x  \n");

        // AI推理说明（截断防止太长）
        String reasoning = decision.getReasoning();
        md.append("🧠 **AI推理说明：** ").append(reasoning != null ? reasoning : "（无详细说明）").append("\n");

        md.append("━━━━━━━━━━━━━━━━━━━━━━━\n\n");

        // =============== 💼 账户状态（增强版） ===================
        // 获取当前价格和开仓价格
        double currentPrice = data.getCurrentPrice();  // 当前市场价格
        double entryPrice = portfolio.getEntryPrice() != null ? portfolio.getEntryPrice() : currentPrice;  // 开仓均价，若无则用当前价
        md.append("### 💼 账户状态\n");
        // 💵 总资产
        md.append("💵 **账户总资产：** $")
                .append(String.format("%.2f", portfolio.getTotalValue() != null ? portfolio.getTotalValue() : 0.0))
                .append("  \n");
        // 💰 可用现金
        md.append("💰 **可用现金：** $")
                .append(String.format("%.2f", portfolio.getCash() != null ? portfolio.getCash() : 0.0))
                .append("  \n");
        // 📊 持仓数量
        md.append("📊 **持仓数量：** ")
                .append(String.format("%.4f", portfolio.getPosition() != null ? portfolio.getPosition() : 0.0))
                .append(" ").append(portfolio.getSymbol() != null ? portfolio.getSymbol() : "N/A")
                .append("  \n");
        // 🧭 持仓方向
        md.append("🧭 **方向：** ")
                .append(portfolio.getDirection() != null ? portfolio.getDirection() : "NONE")
                .append("  \n");
        // 🎯 开仓均价
        md.append("🎯 **开仓均价：** $")
                .append(String.format("%.2f", portfolio.getEntryPrice() != null ? portfolio.getEntryPrice() : 0.0))
                .append("  \n");
        // 💹 标记价格（Bybit Mark Price）
        md.append("💹 **标记价格：** $")
                .append(String.format("%.2f", portfolio.getMarkPrice() != null ? portfolio.getMarkPrice() : 0.0))
                .append("  \n");
        // 🧮 占用保证金
        md.append("🧮 **占用保证金：** $")
                .append(String.format("%.2f", portfolio.getMarginUsed() != null ? portfolio.getMarginUsed() : 0.0))
                .append("  \n");
        // ⚠️ 强平价格
        md.append("⚠️ **强平价格：** $")
                .append(String.format("%.2f", portfolio.getLiquidationPrice() != null ? portfolio.getLiquidationPrice() : 0.0))
                .append("  \n");
        // 📉 未实现盈亏（带红/绿标识）
        double unrealisedPnL = portfolio.getUnrealisedPnL() != null ? portfolio.getUnrealisedPnL() : 0.0;
        String pnlEmoji = unrealisedPnL >= 0 ? "🟢" : "🔴";
        String pnlLabel = unrealisedPnL >= 0 ? "盈利" : "亏损";
        md.append(String.format("%s **未实现盈亏：** %s $%.2f  \n", pnlEmoji, pnlLabel, Math.abs(unrealisedPnL)));
        // 📈 保证金收益率（Margin ROI）
        double pnlPercent = portfolio.getPnLPercent() != null ? portfolio.getPnLPercent() : 0.0;
        String roiEmoji = pnlPercent >= 0 ? "🟢" : "🔴";
        md.append(String.format("%s **保证金收益率：** %.2f%%  \n", roiEmoji, pnlPercent));
        md.append("━━━━━━━━━━━━━━━━━━━━━━━\n\n");

        // ========================== 📊 当日交易统计 ==========================
        String currentYear = String.valueOf(LocalDate.now().getYear());
        md.append("### 📊 " + currentYear + "年交易活动统计\n");
        LocalDateTime nowTime = LocalDateTime.now();                         // 当前时间
        LocalDateTime startOfYear = LocalDate.now().withDayOfYear(1)         // 今年第一天
                .atStartOfDay();                                             // 今年 01-01 00:00:00

        List<TradeOrderEntity> todayOrders = tradeOrderRepository.findBySymbolAndCreatedAtBetweenOrderByCreatedAtDesc(
                data.getSymbol(),
                startOfYear,
                nowTime
        );

        long openCount = todayOrders.stream()
                .filter(o -> o.getSide() != null)
                .filter(o -> ("BUY".equalsIgnoreCase(o.getSide()) || "SELL".equalsIgnoreCase(o.getSide())))
                .filter(o -> o.getClosed() == null || !o.getClosed())
                .count();

        long closeCount = todayOrders.stream().filter(o -> Boolean.TRUE.equals(o.getClosed())).count();

        List<TradeOrderEntity> closedOrders = todayOrders.stream()
                .filter(o -> Boolean.TRUE.equals(o.getClosed()))
                .toList();

        long winCount = closedOrders.stream().filter(o -> {
            if (o.getAvgEntryPrice() == null || o.getCloseAmount() == null || o.getSide() == null) return false;
            BigDecimal entry = o.getAvgEntryPrice();
            BigDecimal close = o.getCloseAmount();
            if ("BUY".equalsIgnoreCase(o.getSide())) return close.compareTo(entry) > 0;
            if ("SELL".equalsIgnoreCase(o.getSide())) return close.compareTo(entry) < 0;
            return false;
        }).count();

        long lossCount = closedOrders.size() - winCount;
        double winRate = (winCount + lossCount) > 0 ? (winCount * 100.0 / (winCount + lossCount)) : 0.0;
        md.append(String.format("🟢 " + currentYear + "年开仓次数：%d 次  \n", openCount));
        md.append(String.format("🔵 " + currentYear + "年平仓次数：%d 次  \n", closeCount));
        md.append(String.format("🏆 " + currentYear + "年胜率：%.1f%% (盈利 %d 单 / 亏损 %d 单)  \n", winRate, winCount, lossCount));

        // ========================== 📄 今日详细下单记录 ==========================
        if (!todayOrders.isEmpty()) {
            md.append("\n\n### 📄 " + currentYear + "年详细下单记录\n");
            DateTimeFormatter hhmmss = DateTimeFormatter.ofPattern("HH:mm:ss");

            for (TradeOrderEntity order : todayOrders) {
                boolean isClosed = Boolean.TRUE.equals(order.getClosed());
                String emoji = isClosed ? "📘 平仓" : "📈 开仓";
                String dirText = "BUY".equalsIgnoreCase(order.getSide()) ? "🟢 多头" :
                        "SELL".equalsIgnoreCase(order.getSide()) ? "🔴 空头" : "⚪ 未知";
                String statusText = isClosed ? "✅ 已平仓" : "🕒 持仓中";

                String estRoiText = "--";
                if (order.getPnlPercent() != null) {
                    // 数据库存储为小数（如 -0.025）转换为百分比
                    estRoiText = String.format("%+.2f%%", order.getPnlPercent().doubleValue());
                }

                md.append(String.format(
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
            md.append("📭 " + currentYear + "年暂无下单记录。\n");
        }

        md.append("━━━━━━━━━━━━━━━━━━━━━━━\n");
        md.append("🕒 报告生成时间：").append(LocalDateTime.now().format(formatter)).append("\n");

        // 控制台打印完整报告
        log.info("\n{}", md);

        // ✅ 发送钉钉 Markdown
        DingDingMessageUtil.sendMarkdown("🤖 DeepSeek AI 交易决策报告", md.toString());
    }

    /**
     * 🧠 DeepSeek AI 交易日志推送（Discord版本）
     * - 与钉钉版本内容一致
     * - 包含「当日交易活动统计」与「今日详细下单记录」
     * - 使用 DiscordWebhookService 发送消息
     */
    private void logTradingActivityDiscord(MarketData data, PortfolioStatus portfolio, TradingDecision decision) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            StringBuilder md = new StringBuilder();

            // =============== 报告标题 ===============
            md.append("## 🤖 DeepSeek AI 交易决策报告\n");
            md.append("━━━━━━━━━━━━━━━━━━━━━━━\n\n");

            // =============== ⏰ 基本信息 ===============
            md.append("### ⏰ 基本信息\n");
            md.append("💱 **交易对：** ").append(data.getSymbol()).append("  \n");
            md.append("🧭 **周期：** ").append(data.getPeriod()).append("  \n");
            md.append("💰 **当前价格：** $").append(String.format("%.2f", data.getCurrentPrice())).append("  \n");

            Double realChange24h = calculatePriceChange24h(data.getSymbol());
            if (realChange24h != null)
                md.append("📊 **过去24小时价格变化：** ").append(String.format("%.2f", realChange24h)).append("%  \n");
            if (data.getVolume() != null)
                md.append("📦 **成交量：** ").append(String.format("%.2f", data.getVolume())).append("\n");

            md.append("━━━━━━━━━━━━━━━━━━━━━━━\n\n");

            // =============== 📈 技术指标 ===============
            md.append("### 📈 技术指标分析\n");
            md.append("RSI(14)：**").append(String.format("%.2f", data.getRsi() != null ? data.getRsi() : 0.0)).append("**  ");
            if (data.getMacdDif() != null && data.getMacdDea() != null) {
                md.append("MACD：DIF=").append(String.format("%.4f", data.getMacdDif()))
                        .append("，DEA=").append(String.format("%.4f", data.getMacdDea()))
                        .append("，HIST=").append(String.format("%.4f", data.getMacdHistogram())).append("  ");
            }

            md.append("\n\n### 📉 EMA 均线趋势分析\n");
            md.append(String.format("🔹 **EMA20：** %.2f  \n", data.getEma20() != null ? data.getEma20() : 0.0));
            md.append(String.format("🔹 **EMA50：** %.2f  \n", data.getEma50() != null ? data.getEma50() : 0.0));
            md.append(String.format("🔹 **EMA144：** %.2f  \n", data.getEma144() != null ? data.getEma144() : 0.0));
            md.append(String.format("🔹 **EMA168：** %.2f  \n", data.getEma168() != null ? data.getEma168() : 0.0));
            md.append(String.format("🔹 **EMA288：** %.2f  \n", data.getEma288() != null ? data.getEma288() : 0.0));
            md.append(String.format("🔹 **EMA338：** %.2f  \n", data.getEma338() != null ? data.getEma338() : 0.0));

            if (data.getEma20() != null && data.getEma50() != null) {
                boolean upTrend = data.getEma20() > data.getEma50();
                md.append("📈 **短中期趋势：** ").append(upTrend ? "上升趋势 📈" : "下降趋势 📉").append("  \n");
            }

            if (data.getEma20() != null && data.getEma50() != null && data.getEma144() != null && data.getEma288() != null) {
                boolean bullTrend = data.getEma20() > data.getEma50() && data.getEma50() > data.getEma144() && data.getEma144() > data.getEma288();
                boolean bearTrend = data.getEma20() < data.getEma50() && data.getEma50() < data.getEma144() && data.getEma144() < data.getEma288();
                if (bullTrend) md.append("🟢 **均线排列结构：** 多头排列 🚀  \n");
                else if (bearTrend) md.append("🔴 **均线排列结构：** 空头排列 ⚠️  \n");
                else md.append("⚪ **均线排列结构：** 混合排列 ⚖️  \n");
            }

            md.append("\n📊 **波动性（ATR）：** ");
            md.append(String.format("ATR(3)=%.3f, ATR(14)=%.3f  \n",
                    data.getAtr3() != null ? data.getAtr3() : 0.0,
                    data.getAtr14() != null ? data.getAtr14() : 0.0));

            md.append("━━━━━━━━━━━━━━━━━━━━━━━\n\n");

            // =============== 🎯 决策结果 ===============
            md.append("### 🎯 AI 决策结果\n");
            md.append("🤖 **操作建议：** ").append(decision.getAction()).append("  \n");
            md.append("💪 **置信度：** ").append(String.format("%.1f", decision.getConfidence() * 100)).append("%  \n");
            md.append("💰 **下单量：** ").append(String.format("%.4f", decision.getOrderQty())).append("  \n");
            md.append("📊 **建议仓位：** ").append(String.format("%.1f", decision.getPositionSize() * 100)).append("%  \n");
            md.append("⚙️ **杠杆建议：** ").append(decision.getLeverage()).append("x  \n");
            md.append("🧠 **AI推理说明：** ").append(decision.getReasoning() != null ? decision.getReasoning() : "（无详细说明）").append("\n");

            md.append("━━━━━━━━━━━━━━━━━━━━━━━\n\n");

            // =============== 📊 当日交易活动统计 ===============
            String currentYear = String.valueOf(LocalDate.now().getYear());
            md.append("### 📊 当年交易活动统计\n");
            LocalDateTime nowTime = LocalDateTime.now();                         // 当前时间
            LocalDateTime startOfYear = LocalDate.now().withDayOfYear(1)         // 今年第一天
                    .atStartOfDay();                                             // 今年 01-01 00:00:00

            List<TradeOrderEntity> todayOrders = tradeOrderRepository.findBySymbolAndCreatedAtBetweenOrderByCreatedAtDesc(
                    data.getSymbol(),
                    startOfYear,
                    nowTime
            );

            long openCount = todayOrders.stream()
                    .filter(o -> o.getSide() != null)
                    .filter(o -> ("BUY".equalsIgnoreCase(o.getSide()) || "SELL".equalsIgnoreCase(o.getSide())))
                    .filter(o -> o.getClosed() == null || !o.getClosed())
                    .count();

            long closeCount = todayOrders.stream().filter(o -> Boolean.TRUE.equals(o.getClosed())).count();

            List<TradeOrderEntity> closedOrders = todayOrders.stream()
                    .filter(o -> Boolean.TRUE.equals(o.getClosed()))
                    .toList();

            long winCount = closedOrders.stream().filter(o -> {
                if (o.getAvgEntryPrice() == null || o.getCloseAmount() == null || o.getSide() == null) return false;
                BigDecimal entry = o.getAvgEntryPrice();
                BigDecimal close = o.getCloseAmount();
                if ("BUY".equalsIgnoreCase(o.getSide())) return close.compareTo(entry) > 0;
                if ("SELL".equalsIgnoreCase(o.getSide())) return close.compareTo(entry) < 0;
                return false;
            }).count();

            long lossCount = closedOrders.size() - winCount;
            double winRate = (winCount + lossCount) > 0 ? (winCount * 100.0 / (winCount + lossCount)) : 0.0;

            md.append(String.format("🟢 " + currentYear + "年开仓次数：%d 次  \n", openCount));
            md.append(String.format("🔵 " + currentYear + "年平仓次数：%d 次  \n", closeCount));
            md.append(String.format("🏆 " + currentYear + "年胜率：%.1f%% (盈利 %d 单 / 亏损 %d 单)  \n", winRate, winCount, lossCount));

            // =============== 📄 今日详细下单记录 ===============
            if (!todayOrders.isEmpty()) {
                md.append("\n\n### 📄 " + currentYear + "年详细下单记录\n");
                DateTimeFormatter hhmmss = DateTimeFormatter.ofPattern("HH:mm:ss");
                for (TradeOrderEntity order : todayOrders) {
                    boolean isClosed = Boolean.TRUE.equals(order.getClosed());
                    String emoji = isClosed ? "📘 平仓" : "📈 开仓";
                    String dirText = "BUY".equalsIgnoreCase(order.getSide()) ? "🟢 多头" :
                            "SELL".equalsIgnoreCase(order.getSide()) ? "🔴 空头" : "⚪ 未知";
                    String statusText = isClosed ? "✅ 已平仓" : "🕒 持仓中";

                    String estRoiText = "--";
                    if (order.getPnlPercent() != null) {
                        estRoiText = String.format("%+.2f%%", order.getPnlPercent().doubleValue());
                    }

                    md.append(String.format(
                            "%s | %s | %s | 开仓价: %.2f | 平仓价: %.2f | 盈亏: %s | 杠杆: %.1fx | 状态: %s | 时间: %s  \n",
                            emoji,
                            dirText,
                            order.getSymbol(),
                            order.getAvgEntryPrice() != null ? order.getAvgEntryPrice().doubleValue() : 0.0,
                            order.getCloseAmount() != null ? order.getCloseAmount().doubleValue() : 0.0,
                            estRoiText,
                            order.getLeverage() != null ? order.getLeverage().doubleValue() : 0.0,
                            statusText,
                            order.getCreatedAt().format(hhmmss)
                    ));
                }
            } else {
                md.append("📭 " + currentYear + "年暂无下单记录。\n");
            }

            md.append("━━━━━━━━━━━━━━━━━━━━━━━\n");
            md.append("🕒 报告生成时间：").append(LocalDateTime.now().format(formatter)).append("\n");

            // 控制台打印
            log.info("\n{}", md);

            // ✅ 推送到 Discord
            DiscordWebhookService discord = new DiscordWebhookService();
            discord.sendMessage(md.toString());

        } catch (Exception e) {
            log.error("❌ Discord 推送失败: {}", e.getMessage(), e);
        }
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
     * @param symbol 交易对（如 BTCUSDT）
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