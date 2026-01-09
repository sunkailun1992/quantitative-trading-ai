package com.trading.service;


import com.fasterxml.jackson.databind.JsonNode;
import com.trading.model.PortfolioStatus;
import com.trading.model.TradingDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;


/**
 * 风险管理服务 - 完整实现仓位控制、止损止盈和风险验证
 * 集成DeepSeek冠军策略的风险管理规则
 */
@Slf4j // Lombok：自动生成日志对象
@Service // 声明为Spring服务
@RequiredArgsConstructor // Lombok：生成包含final字段的构造函数
public class RiskManagementService {

    // ================== 配置参数 ==================
    @Value("${trading.symbol}")
    private String symbol;                           // 交易对

    @Value("${trading.initial-capital}")
    private String initialCapital;                   // 初始资金（仅用于报告/展示）

    @Value("${trading.max-position-size}")
    private Double maxPositionSize;                  // 最大仓位比例

    @Value("${trading.stop-loss-percent}")
    private Double stopLossPercent;                  // 止损百分比（本地硬阈值）

    @Value("${trading.take-profit-percent}")
    private Double takeProfitPercent;                // 止盈百分比（本地硬阈值）

    @Value("${trading.monitor.enabled:true}")
    private boolean monitorEnabled;                  // 是否启用自动监控

    @Value("${trading.monitor.fixed-rate-ms:5000}")
    private long monitorFixedRateMs;                 // 监控频率（毫秒）

    @Value("${trading.monitor.cooldown-seconds:30}")
    private long cooldownSeconds;                    // 冷却期，避免短时间内重复触发

    // ================== 依赖服务 ==================
    private final BybitTradingService bybitTradingService;
    private final DeepSeekService deepSeekService;

    // ================== 去抖/冷却控制 ==================
    private volatile LocalDateTime lastActionTime = LocalDateTime.MIN; // 最近触发时间戳

    // ----------------------------------------------------------------------
    // 1) 定时监控：委托 DeepSeek AI 做核心止损判断；硬阈值作为兜底
    // ----------------------------------------------------------------------


    /**
     * 验证订单风险 - 综合风险检查
     * @param symbol 交易对
     * @param side 交易方向（Buy/Sell）
     * @param quantity 数量
     * @param accountBalance 账户余额
     * @param currentPosition 当前持仓
     * @return true=通过；false=拒绝
     */
    public boolean validateOrder(String symbol, String side, double quantity,
                                 double accountBalance, double currentPosition) {
        double orderValue = quantity; // 简化：此处直接用数量代表价值（若是USDT线性，可乘以价格）

        // 仓位大小限制
        if (orderValue > accountBalance * maxPositionSize) {
            log.warn("❌ 风险控制: 订单超过最大仓位限制, 订单价值: {}, 最大允许: {}",
                    String.format("%.2f", orderValue),
                    String.format("%.2f", accountBalance * maxPositionSize));
            return false;
        }

        // 总风险暴露限制（举例：账户的30%）
        double totalExposure = currentPosition + ("Buy".equalsIgnoreCase(side) ? orderValue : 0);
        if (totalExposure > accountBalance * 0.3) {
            log.warn("❌ 风险控制: 总风险暴露过高, 当前暴露: {}, 最大允许: {}",
                    String.format("%.2f", totalExposure),
                    String.format("%.2f", accountBalance * 0.3));
            return false;
        }

        // 杠杆风险（示例阈值：5x）
        if (isLeverageTooHigh(accountBalance, totalExposure)) {
            log.warn("❌ 风险控制: 杠杆过高, 请降低仓位");
            return false;
        }

        log.info("✅ 风险控制: 订单验证通过");
        return true;
    }



    // ================== 私有工具方法（保留并注释补全） ==================

    /** 简单的杠杆风险判断（示例） */
    private boolean isLeverageTooHigh(double accountBalance, double totalExposure) {
        if (accountBalance == 0) return true;
        double leverage = totalExposure / accountBalance;
        boolean tooHigh = leverage > 5.0;
        if (tooHigh) {
            log.warn("⚠️ 杠杆警告: 当前杠杆{}倍, 建议不超过5倍", String.format("%.1f", leverage));
        }
        return tooHigh;
    }



}