package com.trading.job; // 定时任务包路径

import com.trading.model.PortfolioStatus; // 引入账户状态模型
import com.trading.repository.TradeOrderRepository; // 引入订单仓库
import com.trading.service.BybitTradingService; // 引入交易所服务
import lombok.extern.slf4j.Slf4j; // 引入日志注解
import org.springframework.scheduling.annotation.Scheduled; // 引入定时任务注解
import org.springframework.stereotype.Component; // Spring 组件注解

import java.time.LocalDateTime;

/**
 * ⏱ PositionSyncJob
 * -------------------------------------------------
 * 轻量级账户同步定时任务（Light Sync Job）
 * 功能：
 * 1. 定时获取账户真实持仓数据
 * 2. 同步更新数据库未平仓订单
 * 3. 若无未平仓记录则新建
 * ❌ 不做修复系统
 * ❌ 不做强制平仓
 * ❌ 不做异常对账
 */
@Slf4j // 启用日志
@Component // 注册为 Spring Bean
public class PositionSyncJob { // 定时任务类定义

    private final BybitTradingService bybitTradingService; // Bybit 接口服务
    private final TradeOrderRepository tradeOrderRepository; // 订单数据库仓库

    /**
     * 构造函数注入
     */
    public PositionSyncJob(BybitTradingService bybitTradingService, // 注入 Bybit 服务
                           TradeOrderRepository tradeOrderRepository) { // 注入订单仓库
        this.bybitTradingService = bybitTradingService; // 赋值 Bybit 服务
        this.tradeOrderRepository = tradeOrderRepository; // 赋值订单仓库
    }

    /**
     * 🕒 每30分钟执行一次账户同步
     */
    @Scheduled(cron = "0 10 * * * ?") // 定时任务表达式（每30分钟）
    public void syncAccountPosition() { // 定时执行方法

        // 🟢 打印任务启动日志
        log.info("⏱【PositionSyncJob】开始执行账户持仓同步任务"); // 日志输出

        try { // 异常捕获开始

            // 🟢 Step 1: 从 Bybit 获取真实账户状态
            PortfolioStatus portfolio = bybitTradingService.getEnhancedPortfolioStatus(); // 获取账户状态

            // 🟢 Step 2: 获取交易对 symbol
            String symbol = portfolio.getSymbol(); // 读取交易对

            // 🟢 Step 3: 判断账户是否有持仓
            boolean hasPosition = portfolio.getPosition() != null // 判断持仓对象是否为空
                    && portfolio.getPosition() > 0; // 判断持仓数量是否大于0

            // 🟢 Step 4: 如果账户无持仓 → 不处理，直接结束
            if (!hasPosition) { // 判断无持仓
                log.info("✅【PositionSyncJob】账户无持仓，跳过同步"); // 打印日志
                return; // 结束任务
            }

            // 🟢 Step 5: 调用同步方法（只同步，不修复）
            syncPositionOnly(portfolio, symbol); // 调用同步逻辑

            // 🟢 Step 6: 打印任务完成日志
            log.info("✅【PositionSyncJob】账户持仓同步完成"); // 日志输出

        } catch (Exception e) { // 捕获异常
            log.error("❌【PositionSyncJob】账户同步任务异常", e); // 打印异常日志
        }
    }

    /**
     * 🔁 轻量级同步方法
     * 逻辑：
     * - 账户有持仓
     * - 数据库有未平仓 → 更新
     * - 数据库无未平仓 → 新增
     */
    private void syncPositionOnly(PortfolioStatus portfolio, String symbol) { // 同步方法定义

        // 🟢 Step 1: 查询最近订单记录
        var recentOrders = tradeOrderRepository // 调用仓库
                .findTop20BySymbolOrderByCreatedAtDesc(symbol); // 查询最近20条订单

        // 🟢 Step 2: 查找未平仓订单
        var openOrder = recentOrders.stream() // 转换为流
                .filter(o -> Boolean.FALSE.equals(o.getClosed())) // 过滤未平仓订单
                .findFirst() // 取第一条
                .orElse(null); // 不存在返回 null

        // ================================
        // 🟢 情况 A：有未平仓订单 → 同步更新
        // ================================
        if (openOrder != null) { // 判断存在未平仓订单

            openOrder.setQty(java.math.BigDecimal.valueOf(portfolio.getPosition())); // 更新持仓数量
            openOrder.setPrice(java.math.BigDecimal.valueOf(portfolio.getMarkPrice())); // 更新当前价格
            openOrder.setAvgEntryPrice(java.math.BigDecimal.valueOf(portfolio.getEntryPrice())); // 更新开仓均价
            openOrder.setPnlPercent(java.math.BigDecimal.valueOf(portfolio.getPnLPercent())); // 更新盈亏比例
            openOrder.setMarginUsed(java.math.BigDecimal.valueOf(portfolio.getMarginUsed())); // 更新保证金
            openOrder.setUnrealisedPnL(java.math.BigDecimal.valueOf(portfolio.getUnrealisedPnL())); // 更新未实现盈亏
            openOrder.setLiquidationPrice(java.math.BigDecimal.valueOf(portfolio.getLiquidationPrice())); // 更新强平价
            openOrder.setStatus("OPEN"); // 设置状态为持仓中

            tradeOrderRepository.save(openOrder); // 保存更新

            log.info("🔁【PositionSyncJob】同步更新未平仓订单 → qty={}, avgPrice={}, pnl={}%",
                    openOrder.getQty(), // 打印数量
                    openOrder.getAvgEntryPrice(), // 打印均价
                    openOrder.getPnlPercent()); // 打印盈亏率

            return; // 结束方法
        }

        // ================================
        // 🟢 情况 B：无未平仓订单 → 新增同步订单
        // ================================
        var syncOrder = com.trading.entity.TradeOrderEntity.builder() // 构建订单对象
                .orderId("SYNC-" + System.currentTimeMillis()) // 生成同步订单ID
                .symbol(symbol) // 设置交易对
                .side(portfolio.getDirection().equalsIgnoreCase("LONG") ? "BUY" : "SELL") // 设置方向
                .qty(java.math.BigDecimal.valueOf(portfolio.getPosition())) // 设置数量
                .price(java.math.BigDecimal.valueOf(portfolio.getMarkPrice())) // 设置价格
                .avgEntryPrice(java.math.BigDecimal.valueOf(portfolio.getEntryPrice())) // 设置开仓均价
                .leverage(java.math.BigDecimal.ZERO) // 设置杠杆
                .pnlPercent(java.math.BigDecimal.valueOf(portfolio.getPnLPercent())) // 设置盈亏比例
                .status("SYNC_CREATE") // 设置状态
                .createdAt(LocalDateTime.now()) // 设置创建时间
                .comment("定时任务账户同步生成") // 设置备注
                .closed(false) // 设置未平仓
                .closeOrderId(null) // 平仓订单ID为空
                .closeAmount(null) // 平仓价格为空
                .marginUsed(java.math.BigDecimal.valueOf(portfolio.getMarginUsed())) // 设置保证金
                .unrealisedPnL(java.math.BigDecimal.valueOf(portfolio.getUnrealisedPnL())) // 设置未实现盈亏
                .liquidationPrice(java.math.BigDecimal.valueOf(portfolio.getLiquidationPrice())) // 设置强平价
                .build(); // 构建对象

        tradeOrderRepository.save(syncOrder); // 保存新订单

        log.info("🆕【PositionSyncJob】同步新增未平仓订单 → orderId={}, qty={}, avgPrice={}",
                syncOrder.getOrderId(), // 打印订单ID
                syncOrder.getQty(), // 打印数量
                syncOrder.getAvgEntryPrice()); // 打印均价
    }
}
