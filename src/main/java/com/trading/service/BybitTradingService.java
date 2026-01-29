package com.trading.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.aliyun.DingDing;
import com.trading.aliyun.SimpleMarkdownBuilder;
import com.trading.entity.TradeOrderEntity;
import com.trading.entity.WalletSnapshotEntity;
import com.trading.model.MarketData;
import com.trading.model.PortfolioStatus;
import com.trading.entity.PortfolioStatusEntity;
import com.trading.model.TradingDecision;
import com.trading.repository.PortfolioStatusRepository;
import com.trading.repository.StrategyLogRepository;
import com.trading.repository.TradeOrderRepository;
import com.trading.repository.WalletSnapshotRepository;
import com.trading.util.BybitSignatureUtil;
import com.trading.util.DingDingMessageUtil;
import com.trading.util.HttpUtil;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bybit交易服务 - 修复投资组合数据问题
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class BybitTradingService {

    private final HttpUtil httpUtil;
    private final ObjectMapper objectMapper;
    private final BybitSignatureUtil bybitSignatureUtil;
    private final TradeOrderRepository tradeOrderRepository;
    private final WalletSnapshotRepository walletSnapshotRepository;
    private final PortfolioStatusRepository portfolioRepo;

    @Value("${trading.symbol}")
    private String symbol;

    @Value("${bybit.api-key:}")
    private String apiKey;

    @Value("${bybit.api-secret:}")
    private String apiSecret;

    // Bybit API 端点
    private static final String BYBIT_BASE_URL = "https://api.bybit.com";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ========== 市场数据相关方法（使用公开API） ==========

    /**
     * 获取K线数据 - 使用公开API
     */
    public JsonNode getKline(String symbol, String interval, int limit) throws Exception {
        try {
            log.debug("📈 通过公开API获取K线数据: {} {}分钟线 {}条", symbol, interval, limit);

            String url = BYBIT_BASE_URL + "/v5/market/kline" + "?category=linear" + "&symbol=" + symbol + "&interval=" + interval + "&limit=" + limit;

            String response = httpUtil.publicGet(url);
            return objectMapper.readTree(response);

        } catch (Exception e) {
            log.error("❌ 公开API K线数据获取异常: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * 获取最新价格 - 使用公开API
     */
    public double getCurrentPrice() {
        try {
            // 使用15分钟K线获取最新价格
            JsonNode klineData = getKline(symbol, "15", 1);

            if (klineData != null && klineData.has("retCode") && klineData.get("retCode").asInt() == 0 && klineData.has("result") && klineData.get("result").has("list") && klineData.get("result").get("list").size() > 0) {

                JsonNode klineList = klineData.get("result").get("list");
                JsonNode latestKline = klineList.get(0);

                if (latestKline.isArray() && latestKline.size() >= 5) {
                    double price = latestKline.get(4).asDouble();
                    log.info("💰 当前价格: ${}", String.format("%.2f", price));
                    return price;
                }
            }

            log.warn("⚠️ 无法获取当前价格，使用默认值");
            return 50000.0;

        } catch (Exception e) {
            log.error("❌ 当前价格获取失败: {}", e.getMessage());
            return 50000.0;
        }
    }

    // ========== 账户数据相关方法（使用SDK） ==========

    /**
     * 获取钱包余额 - 使用SDK
     */
    public JsonNode getWalletBalance() throws Exception {
        try {
            log.info("💰 获取钱包余额...");

            // 1️⃣ Bybit接口URL
            String baseUrl = "https://api.bybit.com/v5/account/wallet-balance";

            // 2️⃣ 构建查询参数
            Map<String, String> params = new LinkedHashMap<>();
            params.put("accountType", "UNIFIED"); // 查询统一账户余额

            // 3️⃣ 调用 HttpUtil.signedGet（自动生成签名+Header）
            String response = httpUtil.signedGet(baseUrl, apiKey, apiSecret, params);
            log.info("📩 钱包余额响应: {}", response);

            // 5️⃣ 解析 JSON 响应
            JsonNode result = objectMapper.readTree(response);

            // 6️⃣ 校验接口返回
            if (result.has("retCode") && result.get("retCode").asInt() == 0) {
                log.info("✅ 钱包余额获取成功");
            } else {
                int retCode = result.path("retCode").asInt(-1);
                String retMsg = result.path("retMsg").asText("未知错误");
                log.error("❌ 获取钱包余额失败: {} - {}", retCode, retMsg);
                throw new RuntimeException("获取钱包余额失败: " + retMsg);
            }

            // 7️⃣ 保存钱包快照到数据库
            try {
                double totalEquity = result.path("result").path("list").get(0).path("totalEquity").asDouble(0);
                double availableBalance = result.path("result").path("list").get(0)
                        .path("coin").get(0).path("walletBalance").asDouble(0);

                walletSnapshotRepository.save(new WalletSnapshotEntity(
                        null,
                        BigDecimal.valueOf(totalEquity),
                        BigDecimal.valueOf(availableBalance),
                        LocalDateTime.now()
                ));

                log.info("💾 钱包快照保存成功 → totalEquity={}, availableBalance={}",
                        totalEquity, availableBalance);

            } catch (Exception ex) {
                log.warn("⚠️ 钱包快照写入数据库失败: {}", ex.getMessage());
            }
            return result;

        } catch (Exception e) {
            log.error("获取钱包余额失败: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * 获取真实的投资组合状态 - 修复版本
     */
    public PortfolioStatus getRealPortfolioStatus() {
        try {
            log.info("🔍 开始获取真实账户数据...");

            JsonNode walletBalance = getWalletBalance();
            double currentPrice = getCurrentPrice();

            return parsePortfolioFromWalletBalance(walletBalance, currentPrice);

        } catch (Exception e) {
            log.error("获取真实投资组合失败: {}", e.getMessage(), e);
            return createFallbackPortfolioStatus();
        }
    }

    /**
     * 从钱包余额解析投资组合状态（兼容 Bybit 统一账户 V5 接口）
     * --------------------------------------------------------
     * 1) 解析统一账户返回，获取 totalEquity / 可用余额 等基础资金信息；
     * 2) 若 totalAvailableBalance 缺失，则从 coin 数组推断 USDT 可用余额；
     * 3) 该方法不读取持仓接口（不含逐仓细节），新增的持仓相关字段统一置0或NONE；
     * 4) 返回的 PortfolioStatus 可被 getEnhancedPortfolioStatus 再次补完持仓信息。
     *
     * @param walletBalance Bybit /v5/account/wallet-balance 的响应Json
     * @param currentPrice  市场当前价（兜底作为默认开仓价）
     */
    private PortfolioStatus parsePortfolioFromWalletBalance(JsonNode walletBalance, double currentPrice) {
        try {
            // === 初始化基础变量（资金维度） ===
            double totalValue = 0.0;   // 总权益 totalEquity
            double cash = 0.0;   // 可用余额（优先 totalAvailableBalance）
            double position = 0.0;   // BTC（或净持仓）数量——此方法不查持仓接口，默认0
            double pnlPercent = 0.0;   // 盈亏百分比（账户级，粗略估算）
            String direction = "NONE";// 持仓方向（钱包维度未知，默认 NONE）
            double entryPrice = currentPrice; // 默认开仓价使用当前价

            // === 新增字段（钱包维度未知，统一置默认值；由增强方法再补充） ===
            double markPrice = 0.0; // 标记价格（钱包接口无 → 默认0）
            double marginUsed = 0.0; // 占用保证金（钱包接口无 → 默认0）
            double unrealisedPnL = 0.0; // 未实现盈亏（尽量从账户统计拿 totalPerpUPL，拿不到置0）
            double liquidationPrice = 0.0; // 强平价格（钱包接口无 → 默认0）

            log.info("🔍 开始解析统一账户钱包余额数据...");
            log.debug("钱包余额原始数据: {}", walletBalance);

            // === 1) 基本结构校验 ===
            if (walletBalance == null
                    || !walletBalance.has("result")
                    || !walletBalance.get("result").has("list")
                    || !walletBalance.get("result").get("list").isArray()
                    || walletBalance.get("result").get("list").isEmpty()) {
                log.error("❌ 钱包余额结构异常，无法解析，使用兜底Portfolio");
                return createFallbackPortfolioStatus();
            }

            // === 2) 取统一账户对象（一般只有一个） ===
            JsonNode mainAccount = walletBalance.get("result").get("list").get(0);
            log.info("📊 账户信息: type={}, status={}",
                    mainAccount.path("accountType").asText("N/A"),
                    mainAccount.path("status").asText("N/A"));

            // === 3) 解析总权益（优先 totalEquity → 退回 totalWalletBalance） ===
            if (mainAccount.has("totalEquity") && !mainAccount.get("totalEquity").asText("").isEmpty()) {
                totalValue = mainAccount.get("totalEquity").asDouble(0.0);
            } else if (mainAccount.has("totalWalletBalance") && !mainAccount.get("totalWalletBalance").asText("").isEmpty()) {
                totalValue = mainAccount.get("totalWalletBalance").asDouble(0.0);
            }
            log.info("   💰 总权益: ${}", String.format("%.2f", totalValue));

            // === 4) 解析可用余额（优先 totalAvailableBalance；否则从 coin 数组推断 USDT） ===
            if (mainAccount.has("totalAvailableBalance") && !mainAccount.get("totalAvailableBalance").asText("").isEmpty()) {
                cash = mainAccount.get("totalAvailableBalance").asDouble(0.0);
                log.info("   ✅ 使用 totalAvailableBalance: ${}", String.format("%.2f", cash));
            } else {
                log.warn("⚠️ totalAvailableBalance 为空，尝试从 coin 数组中获取 USDT 可用余额");
                cash = parseUSDTBalanceFromCoins(mainAccount); // 你已有的方法

                // 仍为0则回退 totalWalletBalance
                if (cash == 0.0 && mainAccount.has("totalWalletBalance") && !mainAccount.get("totalWalletBalance").asText("").isEmpty()) {
                    cash = mainAccount.get("totalWalletBalance").asDouble(0.0);
                    log.info("   🔄 使用 totalWalletBalance 兜底: ${}", String.format("%.2f", cash));
                }
            }

            // === 5) 账户层未实现盈亏（若提供 totalPerpUPL 或 coin[].unrealisedPnl 则取；否则0） ===
            // 这里复用你之前的 parseUnrealisedPnl(mainAccount) 工具，取账户聚合的未实现盈亏
            unrealisedPnL = parseUnrealisedPnl(mainAccount);

            // === 6) 粗略计算账户维度盈亏百分比（注意：非逐仓口径，仅用于钱包视角展示） ===
            if (totalValue > 0 && unrealisedPnL != 0.0) {
                // 账户收益率 ≈ 未实现盈亏 / (总权益 - 未实现盈亏)
                pnlPercent = (unrealisedPnL / (totalValue - unrealisedPnL)) * 100.0;
                log.info("   📈 未实现盈亏估算: ${} ({}%)",
                        String.format("%.2f", unrealisedPnL),
                        String.format("%.2f", pnlPercent));
            }

            // === 7) 若 totalValue=0 而 cash 或 position>0，尝试重算 totalValue（兜底） ===
            if (totalValue == 0.0 && (cash > 0.0 || position > 0.0)) {
                totalValue = cash + (position * currentPrice) + unrealisedPnL;
                log.info("   🔄 重新计算总权益(兜底): ${}", String.format("%.2f", totalValue));
            }

            // === 8) 构造 PortfolioStatus（使用无参构造 + setter，避免构造器签名报错） ===
            PortfolioStatus portfolio = new PortfolioStatus(); // 无参构造，下面逐个set

            // —— 资金相关 ——
            portfolio.setTotalValue(totalValue);     // 总权益
            portfolio.setCash(cash);                 // 可用余额
            portfolio.setPosition(position);         // 钱包维度未知，置0（增强方法再补）
            portfolio.setPnLPercent(pnlPercent);     // 账户层收益率（粗略）
            portfolio.setSymbol(symbol);             // 交易对（成员变量）
            portfolio.setUpdateTime(LocalDateTime.now()); // 更新时间

            // —— 持仓与价格相关（钱包层未知，先置默认值） ——
            portfolio.setDirection("NONE");          // 钱包视角不含方向
            portfolio.setEntryPrice(entryPrice);     // 兜底开仓价=当前价
            portfolio.setMarkPrice(markPrice);       // 标记价=0（增强方法再补）
            portfolio.setMarginUsed(marginUsed);     // 占用保证金=0
            portfolio.setUnrealisedPnL(unrealisedPnL);// 未实现盈亏（账户聚合）
            portfolio.setLiquidationPrice(liquidationPrice); // 强平价=0

            // === 9) 打印最终解析结果（钱包维度） ===
            log.info("🎯 投资组合解析完成(钱包维度):");
            log.info("   总资产: ${}", String.format("%.2f", portfolio.getTotalValue()));
            log.info("   可用现金: ${}", String.format("%.2f", portfolio.getCash()));
            log.info("   当前持仓数量: {}", String.format("%.4f", portfolio.getPosition()));
            log.info("   钱包收益率(粗略): {}%", String.format("%.2f", portfolio.getPnLPercent()));
            log.info("   方向: {}", portfolio.getDirection());
            log.info("   兜底开仓价: ${}", String.format("%.2f", portfolio.getEntryPrice()));
            log.info("   标记价(钱包层): ${}", String.format("%.2f", portfolio.getMarkPrice()));
            log.info("   占用保证金(钱包层): ${}", String.format("%.2f", portfolio.getMarginUsed()));
            log.info("   未实现盈亏(账户聚合): ${}", String.format("%.2f", portfolio.getUnrealisedPnL()));
            log.info("   强平价格(钱包层): ${}", String.format("%.2f", portfolio.getLiquidationPrice()));

            // === 10) 返回结果（增强逻辑由 getEnhancedPortfolioStatus 再补充持仓细节） ===
            return portfolio;

        } catch (Exception e) {
            // 任意异常 → 兜底
            log.error("❌ 解析投资组合数据失败: {}", e.getMessage(), e);
            return createFallbackPortfolioStatus();
        }
    }

    /**
     * 从 coin 数组解析 USDT 可用余额
     * 优先使用 availableToWithdraw → walletBalance → equity
     */
    private double parseUSDTBalanceFromCoins(JsonNode mainAccount) {
        try {
            if (!mainAccount.has("coin")) {
                log.warn("⚠️ mainAccount 没有 coin 字段");
                return 0.0;
            }

            for (JsonNode coin : mainAccount.get("coin")) {
                String coinName = coin.path("coin").asText("Unknown");
                if ("USDT".equalsIgnoreCase(coinName)) {

                    if (coin.has("availableToWithdraw") && !coin.get("availableToWithdraw").asText("").isEmpty()) {
                        double val = coin.get("availableToWithdraw").asDouble();
                        log.info("   💵 USDT 可提余额: ${}", String.format("%.2f", val));
                        return val;
                    }

                    if (coin.has("walletBalance") && !coin.get("walletBalance").asText("").isEmpty()) {
                        double val = coin.get("walletBalance").asDouble();
                        log.info("   💵 USDT 钱包余额: ${}", String.format("%.2f", val));
                        return val;
                    }

                    if (coin.has("equity")) {
                        double val = coin.get("equity").asDouble();
                        log.info("   💵 USDT 总权益(兜底): ${}", String.format("%.2f", val));
                        return val;
                    }
                }
            }

            log.warn("⚠️ 未找到 USDT 相关 coin 节点");
            return 0.0;
        } catch (Exception e) {
            log.error("❌ 解析 USDT 余额失败: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * 从coin数组解析BTC持仓
     */
    private double parseBTCPositionFromCoins(JsonNode mainAccount) {
        try {
            if (mainAccount.has("coin")) {
                JsonNode coins = mainAccount.get("coin");
                for (JsonNode coin : coins) {
                    String coinName = coin.has("coin") ? coin.get("coin").asText() : "Unknown";
                    if ("BTC".equals(coinName)) {
                        if (coin.has("walletBalance")) {
                            double btcBalance = coin.get("walletBalance").asDouble();
                            log.info("   BTC钱包余额: {}", String.format("%.6f", btcBalance));
                            return btcBalance;
                        }
                    }
                }
            }
            log.info("   BTC持仓: 0 (未找到BTC余额)");
            return 0.0;
        } catch (Exception e) {
            log.error("❌ 解析BTC持仓失败: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * 解析未实现盈亏
     */
    private double parseUnrealisedPnl(JsonNode mainAccount) {
        try {
            // 先尝试从账户级别获取
            if (mainAccount.has("totalPerpUPL")) {
                double totalPerpUPL = mainAccount.get("totalPerpUPL").asDouble();
                if (totalPerpUPL != 0) {
                    log.info("   永续合约未实现盈亏: ${}", String.format("%.2f", totalPerpUPL));
                    return totalPerpUPL;
                }
            }

            // 从coin数组汇总未实现盈亏
            if (mainAccount.has("coin")) {
                JsonNode coins = mainAccount.get("coin");
                double totalUnrealisedPnl = 0.0;
                for (JsonNode coin : coins) {
                    if (coin.has("unrealisedPnl")) {
                        double coinUnrealisedPnl = coin.get("unrealisedPnl").asDouble();
                        totalUnrealisedPnl += coinUnrealisedPnl;
                        if (coinUnrealisedPnl != 0) {
                            String coinName = coin.has("coin") ? coin.get("coin").asText() : "Unknown";
                            log.info("   {}未实现盈亏: ${}", coinName, String.format("%.2f", coinUnrealisedPnl));
                        }
                    }
                }
                if (totalUnrealisedPnl != 0) {
                    return totalUnrealisedPnl;
                }
            }

            log.info("   未实现盈亏: $0");
            return 0.0;
        } catch (Exception e) {
            log.error("❌ 解析未实现盈亏失败: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * 创建备用投资组合状态（仅当真实数据获取完全失败时使用）
     */
    private PortfolioStatus createFallbackPortfolioStatus() {
        // ⚠️ 提醒：当前使用兜底数据
        log.warn("🔄 使用备用投资组合数据");

        // 1) 获取当前市价，作为兜底的开仓均价参考
        double currentPrice = getCurrentPrice();

        // 2) 设定兜底的总资产
        double totalValue = 1000.0;

        // 3) 使用无参构造，逐项通过 setter 赋值（避免构造器签名不匹配报错）
        PortfolioStatus fallback = new PortfolioStatus();

        // —— 基础资金维度 ——
        fallback.setTotalValue(totalValue);          // 总权益
        fallback.setCash(800.0);                     // 可用现金（兜底示例）
        fallback.setPosition(0.002);                 // 持仓数量（兜底示例）
        fallback.setPnLPercent(5.5);                 // 账户层盈亏百分比（兜底示例）
        fallback.setSymbol(symbol);                  // 交易对
        fallback.setUpdateTime(LocalDateTime.now()); // 更新时间

        // —— 持仓/价格维度（兜底） ——
        fallback.setDirection("NONE");               // 方向未知 → NONE
        fallback.setEntryPrice(currentPrice);        // 兜底开仓均价=当前市价

        // —— 新增的明细字段（钱包层拿不到真实值，先置0，待增强方法补齐） ——
        fallback.setMarkPrice(0.0);                  // 标记价格（增强方法中用持仓接口填充）
        fallback.setMarginUsed(0.0);                 // 占用保证金（增强方法中填充）
        fallback.setUnrealisedPnL(0.0);              // 未实现盈亏（增强方法中填充）
        fallback.setLiquidationPrice(0.0);           // 强平价格（增强方法中填充）

        // 4) 打印兜底结果
        log.info("✅ 创建备用Portfolio状态完成: 总资产=${}, 持仓={}, 方向={}, 开仓价=${}",
                String.format("%.2f", totalValue),
                String.format("%.4f", fallback.getPosition()),
                fallback.getDirection(),
                String.format("%.2f", fallback.getEntryPrice()));

        // 5) 返回兜底对象
        return fallback;
    }

    /**
     * 获取持仓信息 - 增强版（直接输出方向、开仓价、盈亏、收益率）
     */
    public com.fasterxml.jackson.databind.JsonNode getPositionInfo() throws Exception {
        try {
            log.info("📊 开始获取持仓信息...");

            // 1️⃣ 构造请求参数
            Map<String, String> params = new HashMap<>();
            params.put("category", "linear"); // 线性合约（USDT本位）
            params.put("symbol", symbol);     // 当前交易对

            // 2️⃣ 发送签名请求（GET）
            String url = BYBIT_BASE_URL + "/v5/position/list";
            String response = httpUtil.signedGet(url, apiKey, apiSecret, params);

            // 3️⃣ 解析响应为JSON
            JsonNode root = objectMapper.readTree(response);
            int retCode = root.path("retCode").asInt(-1);

            // 4️⃣ 检查接口返回是否成功
            if (retCode != 0) {
                String retMsg = root.path("retMsg").asText("未知错误");
                log.error("❌ 持仓信息获取失败: {} - {}", retCode, retMsg);
                return root; // 返回原始结果（含错误信息）
            }
            log.info("✅ 持仓信息获取成功");

            // 5️⃣ 获取持仓列表节点
            JsonNode listNode = root.path("result").path("list");
            if (!listNode.isArray() || listNode.size() == 0) {
                log.info("📭 当前无持仓: {}", symbol);
                return root; // 没有任何仓位
            }

            // 6️⃣ 遍历每个持仓
            for (JsonNode position : listNode) {

                // 持仓数量
                double size = position.path("size").asDouble(0.0);
                if (size <= 0) continue; // 没仓位直接跳过

                // 持仓方向（Buy=多头，Sell=空头）
                String side = position.path("side").asText("N/A");

                // 开仓均价（成本价）
                double entryPrice = position.path("avgPrice").asDouble(0.0);

                // 当前标记价格（系统参考价）
                double markPrice = position.path("markPrice").asDouble(0.0);

                // 杠杆倍数
                double leverage = position.path("leverage").asDouble(0.0);

                // 强平价格（风险监控用）
                double liqPrice = position.path("liqPrice").asDouble(0.0);

                // 占用保证金（Bybit返回字段）
                double positionIM = position.path("positionIM").asDouble(0.0);

                // 7️⃣ 计算未实现盈亏
                double pnl = 0.0;
                if ("Buy".equalsIgnoreCase(side)) {
                    // 多头：价格上涨盈利
                    pnl = (markPrice - entryPrice) * size;
                } else if ("Sell".equalsIgnoreCase(side)) {
                    // 空头：价格下跌盈利
                    pnl = (entryPrice - markPrice) * size;
                }

                // 8️⃣ 计算保证金收益率
                double pnlRateMargin = 0.0;
                if (positionIM > 0) {
                    pnlRateMargin = (pnl / positionIM) * 100.0;
                }

                // 盈亏符号与方向描述
                String pnlSign = pnl >= 0 ? "🟢 盈利" : "🔴 亏损";
                String direction = "Buy".equalsIgnoreCase(side) ? "多头" : "空头";

                // 9️⃣ 打印日志
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                log.info("📈 持仓详情：{}", symbol);
                log.info("   持仓方向: {}", direction);
                log.info("   仓位数量: {}", String.format("%.4f", size));
                log.info("   杠杆倍数: {}x", String.format("%.0f", leverage));
                log.info("   开仓均价: ${}", String.format("%.2f", entryPrice));
                log.info("   标记价格: ${}", String.format("%.2f", markPrice));
                log.info("   强平价格: ${}", String.format("%.2f", liqPrice));
                log.info("   未实现盈亏: {} ${}", pnlSign, String.format("%.2f", pnl));
                log.info("   保证金收益率: {} {}", pnlSign, String.format("%+.2f%%", pnlRateMargin));
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            }

            return root;
        } catch (Exception e) {
            log.error("获取持仓信息失败: ", e);
            throw e;
        }
    }

    /**
     * 获取增强版投资组合状态（包含真实持仓、保证金收益率、强平价等信息）
     * --------------------------------------------------------
     * 此方法：
     * 1️⃣ 调用钱包接口获取基础状态；
     * 2️⃣ 调用 Bybit 持仓接口获取实时仓位；
     * 3️⃣ 计算盈亏、方向、保证金收益率；
     * 4️⃣ 更新 PortfolioStatus；
     * 5️⃣ 保存快照至数据库。
     */
    public PortfolioStatus getEnhancedPortfolioStatus() {
        try {
            // 🟢 打印入口日志
            log.info("🔍 获取增强投资组合状态...");

            // === 1️⃣ 获取基础账户信息（钱包层） ===
            PortfolioStatus basicStatus = getRealPortfolioStatus(); // 获取 totalValue, cash 等
            basicStatus.setDirection("NONE");  // 默认无方向
            basicStatus.setEntryPrice(0.0);    // 默认开仓价
            basicStatus.setPnLPercent(0.0);    // 默认盈亏%
            basicStatus.setMarkPrice(0.0);     // 默认标记价
            basicStatus.setMarginUsed(0.0);    // 默认保证金
            basicStatus.setUnrealisedPnL(0.0); // 默认盈亏金额
            basicStatus.setLiquidationPrice(0.0); // 默认强平价

            // === 2️⃣ 调用 Bybit 实时持仓接口 ===
            JsonNode positionInfo = getPositionInfo();

            // 判空与结构验证
            if (positionInfo == null
                    || !positionInfo.has("result")
                    || !positionInfo.path("result").has("list")
                    || !positionInfo.path("result").get("list").isArray()) {
                log.warn("⚠️ 持仓信息为空或格式异常，使用基础钱包状态。");
                return basicStatus;
            }

            JsonNode positionList = positionInfo.path("result").path("list");
            if (positionList.isEmpty()) {
                log.info("📭 当前无持仓记录 → 使用基础钱包状态。");
                basicStatus.setPosition(0.0);
                basicStatus.setPnLPercent(0.0);
                basicStatus.setDirection("NONE");
                basicStatus.setEntryPrice(0.0);
                return basicStatus;
            }

            // === 3️⃣ 初始化合并变量 ===
            double totalPosition = 0.0;        // 净持仓量（多正空负）
            double weightedEntryCost = 0.0;    // 加权开仓价分子
            double totalAbsSize = 0.0;         // 加权开仓价分母
            double totalUnrealisedPnl = 0.0;   // 总未实现盈亏
            double totalMarginUsed = 0.0;      // 总保证金
            double liquidationPrice = 0.0;     // 最新强平价格
            double markPrice = 0.0;            // 最新标记价格
            String direction = "NONE";         // 最终方向
            boolean hasPosition = false;       // 是否存在持仓

            // === 4️⃣ 遍历持仓列表 ===
            for (JsonNode pos : positionList) {
                double size = pos.path("size").asDouble(0.0); // 仓位数量
                if (size <= 0) continue; // 无仓位跳过

                hasPosition = true; // 存在仓位标记
                String side = pos.path("side").asText("Buy"); // Buy=多头 Sell=空头
                double entryPrice = pos.path("avgPrice").asDouble(0.0); // 开仓均价
                markPrice = pos.path("markPrice").asDouble(0.0); // 标记价格
                double positionIM = pos.path("positionIM").asDouble(0.0); // 占用保证金
                double unrealisedPnl = pos.path("unrealisedPnl").asDouble(0.0); // 未实现盈亏
                liquidationPrice = pos.path("liqPrice").asDouble(0.0); // 强平价格

                // === 计算浮动盈亏 ===
                double pnl = "Sell".equalsIgnoreCase(side)
                        ? (entryPrice - markPrice) * size // 空头
                        : (markPrice - entryPrice) * size; // 多头

                // === 计算保证金收益率 ===
                double pnlRateMargin = positionIM > 0 ? (pnl / positionIM) * 100.0 : 0.0;

                // === 累加统计 ===
                totalUnrealisedPnl += pnl;
                totalMarginUsed += positionIM;
                weightedEntryCost += entryPrice * size;
                totalAbsSize += size;
                totalPosition += "Sell".equalsIgnoreCase(side) ? -size : size;
                direction = "Sell".equalsIgnoreCase(side) ? "空头" : "多头";

                // === 输出详细日志 ===
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                log.info("📈 持仓方向: {}", direction);
                log.info("   仓位数量: {}", String.format("%.4f", size));
                log.info("   开仓均价: ${}", String.format("%.2f", entryPrice));
                log.info("   标记价格: ${}", String.format("%.2f", markPrice));
                log.info("   占用保证金: ${}", String.format("%.2f", positionIM));
                log.info("   未实现盈亏: {} ${}", pnl >= 0 ? "🟢 盈利" : "🔴 亏损", String.format("%.2f", pnl));
                log.info("   强平价格: ${}", String.format("%.2f", liquidationPrice));
                log.info("   保证金收益率: {} {}", pnl >= 0 ? "🟢 盈利" : "🔴 亏损", String.format("%+.2f%%", pnlRateMargin));
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            }

            // === 5️⃣ 汇总投资组合层面数据 ===
            if (hasPosition) {
                double netPosition = Math.abs(totalPosition);
                double avgEntryPrice = totalAbsSize > 0 ? weightedEntryCost / totalAbsSize : 0.0;
                double marginPnlPercent = totalMarginUsed > 0
                        ? (totalUnrealisedPnl / totalMarginUsed) * 100.0
                        : 0.0;

                // === 更新 PortfolioStatus 对象 ===
                basicStatus.setPosition(netPosition);             // 持仓数量
                basicStatus.setDirection(direction);              // 多/空方向
                basicStatus.setEntryPrice(avgEntryPrice);         // 加权均价
                basicStatus.setPnLPercent(marginPnlPercent);      // 保证金收益率
                basicStatus.setUpdateTime(LocalDateTime.now());   // 更新时间
                basicStatus.setMarkPrice(markPrice);              // 标记价格
                basicStatus.setMarginUsed(totalMarginUsed);       // 占用保证金
                basicStatus.setUnrealisedPnL(totalUnrealisedPnl); // 未实现盈亏
                basicStatus.setLiquidationPrice(liquidationPrice);// 强平价格

                // === 打印结果汇总日志 ===
                log.info("✅ 当前方向: {}, 净持仓: {}, 均价: ${}, 标记价: ${}, 强平价: ${}, 保证金: ${}, 未实现盈亏: ${}, 保证金收益率: {}%",
                        direction,
                        String.format("%.4f", netPosition),
                        String.format("%.2f", avgEntryPrice),
                        String.format("%.2f", markPrice),
                        String.format("%.2f", liquidationPrice),
                        String.format("%.2f", totalMarginUsed),
                        String.format("%.2f", totalUnrealisedPnl),
                        String.format("%.2f", marginPnlPercent));
            } else {
                // 无仓位 → 清空
                basicStatus.setPosition(0.0);
                basicStatus.setPnLPercent(0.0);
                basicStatus.setDirection("NONE");
                basicStatus.setEntryPrice(0.0);
                basicStatus.setMarkPrice(0.0);
                basicStatus.setMarginUsed(0.0);
                basicStatus.setUnrealisedPnL(0.0);
                basicStatus.setLiquidationPrice(0.0);
                log.info("📭 当前无持仓 → 已清空盈亏与方向信息。");
            }

            // === 6️⃣ 保存数据库快照 ===
            try {
                PortfolioStatusEntity entity = new PortfolioStatusEntity(basicStatus);
                portfolioRepo.save(entity);
                log.info("💾 已保存增强投资组合快照 → {}", entity);
            } catch (Exception dbEx) {
                log.warn("⚠️ 数据库存储失败: {}", dbEx.getMessage());
            }

            // === 7️⃣ 返回增强状态对象 ===
            return basicStatus;

        } catch (Exception e) {
            log.error("❌ 获取增强投资组合状态失败: {}", e.getMessage());
            return getRealPortfolioStatus(); // 兜底回退
        }
    }


    // ========== 交易相关方法 ==========

    /**
     * AI增强版下单接口：
     * 支持 AI 动态传入市场参数（RSI、波动率、成交量等），
     * 自动计算最优杠杆并执行下单。
     *
     * @param symbol       交易对
     * @param side         买卖方向 (Buy / Sell)
     * @param qty          下单数量
     * @param leverageHint AI建议杠杆
     * @return Bybit返回的JsonNode结果
     */
    public JsonNode placeMarketOrder(String symbol, String side, double qty, int leverageHint, PortfolioStatus portfolio, MarketData md15m, TradingDecision decision) throws Exception {

        // 🟢 Step 1: 打印基础日志
        log.info("🤖【AI智能下单】开始执行：symbol={}, side={}, qty={}", symbol, side, qty);

        // 🟢 Step 2: 设置杠杆（若失败默认10倍）
        boolean leverageSet = setLeverage(symbol, leverageHint);
        if (!leverageSet) {
            log.warn("⚠️ 杠杆设置失败，使用默认10x");
            leverageHint = 10;
        }
        log.info("✅ 最终下单杠杆倍率 → {}x", leverageHint);

        // 🟢 Step 3: 构建下单请求体
        Map<String, Object> orderRequest = new HashMap<>();
        orderRequest.put("category", "linear"); // 线性合约类型
        orderRequest.put("symbol", symbol);     // 设置交易对
        // 转换买卖方向（兼容 CLOSE_LONG / CLOSE_SHORT）
        if (side.equals("BUY")) {
            orderRequest.put("side", "Buy");
        } else if (side.equals("SELL")) {
            orderRequest.put("side", "Sell");
        } else if (side.equals("CLOSE_LONG")) {
            orderRequest.put("side", "Sell");
        } else if (side.equals("CLOSE_SHORT")) {
            orderRequest.put("side", "Buy");
        }
        orderRequest.put("orderType", "Market"); // 市价单
        orderRequest.put("qty", String.valueOf(qty)); // 下单数量（字符串格式）
        orderRequest.put("timeInForce", "GTC"); // 一直有效直到成交

        // 🟢 Step 4: 序列化为 JSON
        String jsonBody = objectMapper.writeValueAsString(orderRequest);
        log.debug("📦 AI下单请求体: {}", jsonBody);

        // 🟢 Step 5: 生成签名并发送 POST 请求
        String url = BYBIT_BASE_URL + "/v5/order/create"; // 下单接口
        Map<String, String> headers = bybitSignatureUtil.generatePostRequestSignature(apiKey, apiSecret, jsonBody); // 生成签名头
        String response = httpUtil.sendAuthenticatedPost(url, headers, jsonBody); // 发送请求
        JsonNode result = objectMapper.readTree(response); // 解析返回结果

        // 🟢 Step 6: 判断下单是否成功
        if (result.has("retCode") && result.get("retCode").asInt() == 0) {
            log.info("✅ AI智能下单成功: {} {}x 杠杆, 数量 {}", side, leverageHint, qty);
            // 提取订单ID
            JsonNode orderResult = result.path("result");
            String orderId = orderResult.path("orderId").asText("N/A");
            try {
                // 🟢 Step 7: 等待Bybit数据同步（延迟2秒再查询）
                Thread.sleep(2000);

                // 🟢 Step 8: 获取最新真实持仓状态
                PortfolioStatus refreshed = getEnhancedPortfolioStatus();

                // 🟢 Step 9: 查询数据库中最近的未平仓订单
                List<TradeOrderEntity> recentOrders = tradeOrderRepository.findTop20BySymbolOrderByCreatedAtDesc(symbol);
                TradeOrderEntity openOrder = recentOrders.stream()
                        .filter(o -> Boolean.FALSE.equals(o.getClosed())) // 筛选未平仓订单
                        .findFirst()
                        .orElse(null);

                // 🟢 Step 10: 判断是否为加仓
                if (openOrder != null) {
                    // ✅ 已有相同方向持仓 → 加仓逻辑
                    log.info("🟢 检测到已有 {} 方向持仓 → 执行加仓合并", side);

                    // 🟢 更新数据库中该订单
                    openOrder.setQty(BigDecimal.valueOf(refreshed.getPosition())); // 更新总持仓数量
                    openOrder.setAvgEntryPrice(BigDecimal.valueOf(refreshed.getEntryPrice())); // 更新开仓均价
                    openOrder.setLeverage(BigDecimal.valueOf(leverageHint)); // 更新杠杆
                    openOrder.setMarginUsed(BigDecimal.valueOf(refreshed.getMarginUsed())); // 更新保证金
                    openOrder.setUnrealisedPnL(BigDecimal.valueOf(refreshed.getUnrealisedPnL())); // 更新未实现盈亏
                    openOrder.setLiquidationPrice(BigDecimal.valueOf(refreshed.getLiquidationPrice())); // 更新强平价
                    openOrder.setPnlPercent(BigDecimal.valueOf(refreshed.getPnLPercent())); // 更新收益率
                    tradeOrderRepository.save(openOrder); // 保存更新

                    log.info("🔁 已更新加仓订单 → 合并后数量={}, 新均价={}", openOrder.getQty(), openOrder.getAvgEntryPrice());
                } else {
                    // 🆕 没有同方向持仓 → 新建订单记录
                    TradeOrderEntity orderRecord = TradeOrderEntity.builder()
                            .orderId(orderId)
                            .symbol(symbol)
                            .side(side.toUpperCase()) // BUY / SELL
                            .qty(BigDecimal.valueOf(qty))
                            .price(BigDecimal.valueOf(md15m.getCurrentPrice()))
                            .avgEntryPrice(portfolio.getEntryPrice() == 0.00 ? BigDecimal.valueOf(md15m.getCurrentPrice()) : BigDecimal.valueOf(portfolio.getEntryPrice()))
                            .leverage(BigDecimal.valueOf(leverageHint))
                            .pnlPercent(BigDecimal.valueOf(portfolio.getPnLPercent()))
                            .status("FILLED")
                            .createdAt(LocalDateTime.now())
                            .comment(decision.getReasoning())
                            .closed(false)
                            .closeOrderId(null)
                            .closeAmount(null)
                            .build();

                    tradeOrderRepository.save(orderRecord);
                    log.info("💾 已保存AI初次下单记录 → {}", orderId);

                    PortfolioStatus refreshedPortfolio = getEnhancedPortfolioStatus(); // 重新获取真实持仓数据

                    // === 更新刚才保存的订单记录 ===
                    orderRecord.setPrice(BigDecimal.valueOf(refreshedPortfolio.getEntryPrice()));
                    orderRecord.setAvgEntryPrice(BigDecimal.valueOf(refreshedPortfolio.getEntryPrice()));
                    orderRecord.setPnlPercent(BigDecimal.valueOf(refreshedPortfolio.getPnLPercent()));
                    orderRecord.setMarginUsed(BigDecimal.valueOf(refreshedPortfolio.getMarginUsed()));
                    orderRecord.setUnrealisedPnL(BigDecimal.valueOf(refreshedPortfolio.getUnrealisedPnL()));
                    orderRecord.setLiquidationPrice(BigDecimal.valueOf(refreshedPortfolio.getLiquidationPrice()));
                    tradeOrderRepository.save(orderRecord);
                    log.info("🔁 已同步更新开仓均价={} 盈亏比={}%",
                            refreshedPortfolio.getEntryPrice(), refreshedPortfolio.getPnLPercent());
                }
            } catch (Exception e) {
                log.warn("⚠️ 保存订单记录失败: {}", e.getMessage());
            }
            // === ✅ 调用钉钉通知方法 ===
            sendOrderNotification(symbol, side, qty, leverageHint);
        } else {
            int retCode = result.path("retCode").asInt(-1);
            String retMsg = result.path("retMsg").asText("未知错误");
            log.error("❌ AI智能下单失败: {} - {}", retCode, retMsg);
            handleOrderError(retCode, retMsg, symbol, side, qty);
        }

        return result;
    }

    /**
     * 处理订单错误
     */
    private void handleOrderError(int retCode, String retMsg, String symbol, String side, double qty) {
        switch (retCode) {
            case 10001:
                log.error("⚠️ 错误处理: API密钥权限不足");
                break;
            case 10002:
                log.error("⚠️ 错误处理: API密钥无效");
                break;
            case 10003:
                log.error("⚠️ 错误处理: 频率限制");
                break;
            case 10004:
                log.error("⚠️ 错误处理: 请求签名错误");
                break;
            case 10005:
                log.error("⚠️ 错误处理: 时间戳过期");
                break;
            case 10006:
                log.error("⚠️ 错误处理: 请求参数错误");
                break;
            case 110001:
                log.error("⚠️ 错误处理: 交易对不存在: {}", symbol);
                break;
            case 110002:
                log.error("⚠️ 错误处理: 订单数量无效: {}", qty);
                break;
            case 110003:
                log.error("⚠️ 错误处理: 订单价格无效");
                break;
            case 110004:
                log.error("⚠️ 错误处理: 订单类型无效");
                break;
            case 110005:
                log.error("⚠️ 错误处理: 订单方向无效: {}", side);
                break;
            case 110006:
                log.error("⚠️ 错误处理: 持仓模式不匹配");
                break;
            case 110007:
                log.error("⚠️ 错误处理: 杠杆无效");
                break;
            case 110008:
                log.error("⚠️ 错误处理: 订单不存在");
                break;
            case 110009:
                log.error("⚠️ 错误处理: 订单已取消或已完成");
                break;
            case 110010:
                log.error("⚠️ 错误处理: 订单修改被拒绝");
                break;
            case 110011:
                log.error("⚠️ 错误处理: 订单已存在");
                break;
            case 110012:
                log.error("⚠️ 错误处理: 仓位不存在");
                break;
            case 110013:
                log.error("⚠️ 错误处理: 仓位已平仓");
                break;
            case 110014:
                log.error("⚠️ 错误处理: 仓位模式不匹配");
                break;
            case 110015:
                log.error("⚠️ 错误处理: 强平中不允许操作");
                break;
            case 110016:
                log.error("⚠️ 错误处理: 资金不足");
                log.error("   建议: 检查账户余额，减少订单数量");
                break;
            case 110017:
                log.error("⚠️ 错误处理: 仓位被锁");
                break;
            case 110018:
                log.error("⚠️ 错误处理: 交易对已暂停");
                break;
            case 110019:
                log.error("⚠️ 错误处理: 委托单不允许市价单");
                break;
            case 110020:
                log.error("⚠️ 错误处理: 订单数量太小");
                break;
            case 110021:
                log.error("⚠️ 错误处理: 订单数量太大");
                break;
            case 110022:
                log.error("⚠️ 错误处理: 订单价格超出范围");
                break;
            case 110023:
                log.error("⚠️ 错误处理: 止损价格无效");
                break;
            case 110024:
                log.error("⚠️ 错误处理: 止盈价格无效");
                break;
            case 110025:
                log.error("⚠️ 错误处理: 触发价格无效");
                break;
            case 110026:
                log.error("⚠️ 错误处理: 订单已提交");
                break;
            case 110027:
                log.error("⚠️ 错误处理: 订单已部分成交");
                break;
            case 110028:
                log.error("⚠️ 错误处理: 订单等待触发");
                break;
            case 110029:
                log.error("⚠️ 错误处理: 订单已触发");
                break;
            case 110030:
                log.error("⚠️ 错误处理: 订单被拒绝");
                break;
            case 110031:
                log.error("⚠️ 错误处理: 订单已过期");
                break;
            default:
                log.error("⚠️ 错误处理: 未知错误代码: {}", retCode);
                break;
        }
    }

    /**
     * 设置合约杠杆（适用于Bybit V5接口）
     * 通过HttpUtil.signedPost发送签名请求
     *
     * @param symbol   交易对
     * @param leverage 杠杆倍数（限制1~20）
     * @return true = 设置成功，false = 失败
     */
    public boolean setLeverage(String symbol, int leverage) {
        try {
            // === 1️⃣ 构造URL ===
            String baseUrl = "https://api.bybit.com/v5/position/set-leverage";

            // === 2️⃣ 构造请求体 ===
            Map<String, String> params = new LinkedHashMap<>();  // 用LinkedHashMap保证顺序
            params.put("category", "linear");
            params.put("symbol", symbol);
            params.put("buyLeverage", String.valueOf(leverage));  // 必须是字符串
            params.put("sellLeverage", String.valueOf(leverage)); // 必须是字符串

            // === 3️⃣ 转换为JSON字符串 ===
            String jsonBody = new ObjectMapper().writeValueAsString(params);
            log.info("📤 杠杆设置请求体: {}", jsonBody);

            // === 4️⃣ 生成签名Header ===
            Map<String, String> headers = bybitSignatureUtil.generatePostRequestSignature(apiKey, apiSecret, jsonBody);

            // === 5️⃣ 发送请求 ===
            String response = httpUtil.sendAuthenticatedPost(baseUrl, headers, jsonBody);

            // === 5️⃣ 解析响应 ===
            JsonNode jsonResponse = new ObjectMapper().readTree(response);
            int retCode = jsonResponse.has("retCode") ? jsonResponse.get("retCode").asInt() : -1;
            String retMsg = jsonResponse.has("retMsg") ? jsonResponse.get("retMsg").asText() : "无返回消息";

            // === 6️⃣ 判断是否成功 ===
            if (retCode == 0) {
                log.info("✅ 杠杆设置成功：{} → {}x", symbol, leverage);
                return true;
            } else if (retCode == 110043) {
                // 特别处理 "leverage not modified" 错误
                log.info("✅ 杠杆已经是目标值，无需修改：{} → {}x", symbol, leverage);
                return true;
            } else {
                log.warn("⚠️ 杠杆设置失败：symbol={}, leverage={}, 错误={}", symbol, leverage, retMsg);
                return false;
            }
        } catch (Exception e) {
            log.error("❌ 设置杠杆异常: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 市价平仓（reduceOnly）：按净方向反向下单，将仓位在市价下全部或部分平掉
     *
     * @param symbol   交易对
     * @param closeQty 想要平掉的数量（<= 当前净持仓），若<=0则自动按全部净持仓
     * @return Bybit返回结果
     */
    public JsonNode closePositionMarket(String symbol, String side, double closeQty, int leverageHint, PortfolioStatus portfolio, MarketData md15m, TradingDecision decision) throws Exception { // 定义平仓方法
        // ✅ 1️⃣ 检查是否有持仓
        if (portfolio.getPosition() == null || portfolio.getPosition() <= 0) {
            log.info("📭 {} 当前无持仓可平", symbol);
            Map<String, Object> fakeOk = new HashMap<>();
            fakeOk.put("retCode", 0);
            fakeOk.put("retMsg", "NO_POSITION");
            fakeOk.put("result", new HashMap<>());
            return objectMapper.valueToTree(fakeOk);
        }
        // ✅ 2️⃣ 校正平仓数量，不能超过当前持仓
        double qty = closeQty > 0 ? Math.min(closeQty, portfolio.getPosition()) : portfolio.getPosition();

        log.info("🔧 计划平仓 → symbol={}, side={}, qty={}, 当前方向={}, 均价={}, 盈亏={:.2f}%",
                symbol, side, qty, portfolio.getDirection(), portfolio.getEntryPrice(), portfolio.getPnLPercent());

        // ✅ 3️⃣ 构建 reduceOnly 市价单请求体
        Map<String, Object> orderRequest = new HashMap<>();
        orderRequest.put("category", "linear");
        orderRequest.put("symbol", symbol);
        // 将业务方向映射为 Bybit 侧的下单方向
        if (side.equals("BUY")) {
            orderRequest.put("side", "Buy");
        } else if (side.equals("SELL")) {
            orderRequest.put("side", "Sell");
        } else if (side.equals("CLOSE_LONG")) {
            orderRequest.put("side", "Sell");
        } else if (side.equals("CLOSE_SHORT")) {
            orderRequest.put("side", "Buy");
        }
        orderRequest.put("orderType", "Market"); // 市价单
        orderRequest.put("qty", String.valueOf(qty)); // 平仓数量
        orderRequest.put("timeInForce", "IOC"); // 立即成交
        orderRequest.put("reduceOnly", true);   // 限制为仅平仓，不开新仓

        // ✅ 4️⃣ 转为 JSON
        String jsonBody = objectMapper.writeValueAsString(orderRequest);
        log.debug("📦 平仓请求体: {}", jsonBody);

        // ✅ 5️⃣ 签名并发送请求
        String url = BYBIT_BASE_URL + "/v5/order/create";
        Map<String, String> headers = bybitSignatureUtil.generatePostRequestSignature(apiKey, apiSecret, jsonBody);
        String response = httpUtil.sendAuthenticatedPost(url, headers, jsonBody);
        JsonNode result = objectMapper.readTree(response);

        // ✅ 6️⃣ 判断是否成功
        if (result.has("retCode") && result.get("retCode").asInt() == 0) {
            JsonNode orderResult = result.path("result");
            String orderId = orderResult.path("orderId").asText("N/A");
            try {
                // 🕒 延迟1秒等待Bybit更新持仓
                Thread.sleep(1200);

                // 🧩 获取最新持仓状态
                PortfolioStatus refreshed = getEnhancedPortfolioStatus();

                // 🧾 查找数据库中最近的未平仓订单
                List<TradeOrderEntity> recentOrders = tradeOrderRepository.findTop20BySymbolOrderByCreatedAtDesc(symbol);
                TradeOrderEntity openOrder = recentOrders.stream()
                        .filter(o -> Boolean.FALSE.equals(o.getClosed()))
                        .findFirst()
                        .orElse(null);

                if (openOrder != null) {
                    // ✅ 判断是否为“部分平仓”情况
                    if (refreshed.getPosition() > 0) {
                        // 📊 部分平仓：更新剩余仓位与盈亏信息
                        log.info("🟡 检测到部分平仓 → 拆分为两条记录（原订单锁定利润，新订单继承剩余仓位）");
                        // 6.3 计算“本次实际平掉数量 = 历史数量 - 剩余数量”
                        double historyQty = openOrder.getQty() == null ? 0.0 : openOrder.getQty().doubleValue(); // 历史开仓数量
                        double closedQty = Math.max(0.0, new BigDecimal(historyQty).subtract(BigDecimal.valueOf(refreshed.getPosition())).setScale(8, RoundingMode.HALF_UP).doubleValue()); // 精确相减并保留小数
                        log.info("🧮 部分/全平数量计算 → 历史数量={}, 剩余数量={}, 实际平掉={}", historyQty, refreshed.getPosition(), closedQty); // 打印计算结果
                        // 1️⃣ 原订单标记部分平仓并锁定利润
                        openOrder.setQty(BigDecimal.valueOf(closedQty));                // 关键：原单数量=本次平掉的数量
                        openOrder.setAvgEntryPrice(BigDecimal.valueOf(portfolio.getEntryPrice())); // 更新开仓均价
                        openOrder.setClosed(true);
                        openOrder.setCloseOrderId(orderId);
                        openOrder.setCloseAmount(BigDecimal.valueOf(md15m.getCurrentPrice())); // 本次部分平仓价格
                        openOrder.setPnlPercent(BigDecimal.valueOf(portfolio.getPnLPercent())); // 当前累计收益
                        openOrder.setCloseComment("部分平仓锁定部分利润: " + decision.getReasoning());
                        openOrder.setMarginUsed(BigDecimal.valueOf(portfolio.getMarginUsed()));
                        openOrder.setUnrealisedPnL(BigDecimal.valueOf(portfolio.getUnrealisedPnL()));
                        openOrder.setLiquidationPrice(BigDecimal.valueOf(portfolio.getLiquidationPrice()));
                        tradeOrderRepository.save(openOrder);

                        // 2️⃣ 新建一条代表“剩余持仓”的记录
                        TradeOrderEntity remainingOrder = TradeOrderEntity.builder()
                                .orderId(orderId + "-R") // 新标识符
                                .symbol(symbol)
                                .side(openOrder.getSide()) // 保留方向
                                .qty(BigDecimal.valueOf(refreshed.getPosition())) // 剩余仓位
                                .price(BigDecimal.valueOf(refreshed.getEntryPrice())) // 当前市价
                                .avgEntryPrice(BigDecimal.valueOf(refreshed.getEntryPrice())) // 新均价
                                .leverage(BigDecimal.valueOf(leverageHint))
                                .pnlPercent(BigDecimal.valueOf(refreshed.getPnLPercent()))
                                .status("PARTIAL")
                                .createdAt(LocalDateTime.now())
                                .closed(false) // 未平仓
                                .comment("继承自部分平仓剩余仓位")
                                .marginUsed(BigDecimal.valueOf(refreshed.getMarginUsed()))
                                .unrealisedPnL(BigDecimal.valueOf(refreshed.getUnrealisedPnL()))
                                .liquidationPrice(BigDecimal.valueOf(refreshed.getLiquidationPrice()))
                                .build();

                        tradeOrderRepository.save(remainingOrder);
                        log.info("💾 已新建剩余持仓记录 → 数量={}, 均价={}", refreshed.getPosition(), refreshed.getEntryPrice());
                    } else {
                        // ✅ 完全平仓：标记 closed=true
                        log.info("🟢 完全平仓完成 → 更新订单为已关闭");
                        openOrder.setAvgEntryPrice(BigDecimal.valueOf(portfolio.getEntryPrice())); // 更新开仓均价
                        openOrder.setClosed(true); // 标记已平仓
                        openOrder.setCloseOrderId(orderId); // 记录平仓订单号
                        openOrder.setCloseAmount(BigDecimal.valueOf(md15m.getCurrentPrice())); // 平仓价格
                        openOrder.setPnlPercent(BigDecimal.valueOf(portfolio.getPnLPercent())); // 最终收益率
                        openOrder.setCloseComment(decision.getReasoning()); // 平仓原因
                        openOrder.setMarginUsed(BigDecimal.valueOf(portfolio.getMarginUsed())); // 保证金
                        openOrder.setUnrealisedPnL(BigDecimal.valueOf(portfolio.getUnrealisedPnL())); // 盈亏金额
                        openOrder.setLiquidationPrice(BigDecimal.valueOf(portfolio.getLiquidationPrice())); // 强平价
                        tradeOrderRepository.save(openOrder); // 保存

                        log.info("💾 已保存完全平仓记录 → {}", orderId);
                    }
                } else {
                    // ⚠️ 未找到对应订单，创建新平仓记录（极端情况）
                    log.warn("⚠️ 未找到可平的开仓订单。");
                }
            } catch (Exception dbEx) {                                     // 捕获入库异常
                log.warn("⚠️ 保存平仓记录失败: {}", dbEx.getMessage());        // 打印警告
            }
            sendCloseNotification(symbol, side, qty);

        } else {
            // ❌ 错误分支
            int retCode = result.path("retCode").asInt(-1);                // 错误码
            String retMsg = result.path("retMsg").asText("未知错误");          // 错误消息
            log.error("❌ 平仓失败: {} - {}", retCode, retMsg);               // 打印错误
            handleOrderError(retCode, retMsg, symbol, side, qty);     // 复用错误处理
        }
        return result;                                                     // 返回原始响应
    }

// ================== ★★★ 新增内容结束（BybitTradingService） ★★★

    /**
     * 🤖 AI 智能下单通知（美化版）
     *
     * @param symbol        交易对
     * @param side          买卖方向（BUY / SELL）
     * @param qty           下单数量
     * @param leverage      杠杆倍数
     * @param executedPrice 成交价格
     */

    /**
     * 🤖 AI 智能下单通知（专业版美化）
     */
    public static void sendOrderNotification(String symbol, String side, double qty, int leverage) {
        try {
            String title = "🤖 AI 智能下单通知";

            // 💡 构建消息体
            String markdown = SimpleMarkdownBuilder.create()
                    .title("🤖 AI 智能下单通知", 3)
                    .text("━━━━━━━━━━━━━━━━━━━━━━━", true)
                    .text("💱 交易对：**" + symbol + "**", true)
                    .text("🧭 方向：**" + (side.equalsIgnoreCase("BUY") ? "BUY 🟢 多头" : "SELL 🔴 空头") + "**", true)
                    .text("⚙️ 杠杆：**" + leverage + "x**", true)
                    .text("📦 数量：**" + String.format("%.4f", qty) + "**", true)
                    .text("🕒 时间：**" + LocalDateTime.now().format(FORMATTER) + "**", true)
                    .text("━━━━━━━━━━━━━━━━━━━━━━━", true)
                    .text("✅ 状态：**已成交 ✅**", true)
                    .text("━━━━━━━━━━━━━━━━━━━━━━━", true)
                    .build();

            DingDingMessageUtil.sendMarkdown(title, markdown);
            log.info("📩 钉钉下单提醒发送成功: {} {} {}x", symbol, side, leverage);
        } catch (Exception e) {
            log.warn("⚠️ 钉钉下单提醒发送失败: {}", e.getMessage());
        }
    }

    /**
     * 📉 AI 智能平仓通知（专业版美化）
     */
    public static void sendCloseNotification(String symbol, String side, double qty) {
        try {
            String title = "📉 AI 智能平仓通知";

            // 💡 构建Markdown文本
            String markdown = SimpleMarkdownBuilder.create()
                    .title("📉 AI 智能平仓通知", 3)
                    .text("━━━━━━━━━━━━━━━━━━━━━━━", true)
                    .text("💱 交易对：**" + symbol + "**", true)
                    .text("🔁 平仓方向：**" + (side.equalsIgnoreCase("SELL") ? "SELL 🔴 空头" : "BUY 🟢 多头") + "**", true)
                    .text("📦 平仓数量：**" + String.format("%.4f", qty) + "**", true)
                    .text("🕒 时间：**" + LocalDateTime.now().format(FORMATTER) + "**", true)
                    .text("━━━━━━━━━━━━━━━━━━━━━━━", true)
                    .text("✅ 状态：**平仓完成 ✅**", true)
                    .text("━━━━━━━━━━━━━━━━━━━━━━━", true)
                    .build();

            DingDingMessageUtil.sendMarkdown(title, markdown);
            log.info("📩 钉钉平仓提醒发送成功: {} {}", symbol, side);
        } catch (Exception e) {
            log.warn("⚠️ 钉钉平仓提醒发送失败: {}", e.getMessage());
        }
    }
}