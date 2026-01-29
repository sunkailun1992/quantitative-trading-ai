package com.trading.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 💼 PortfolioStatus
 * 投资组合状态模型类（业务层）
 * ---------------------------------------------------------
 * 用于实时记录账户的持仓、盈亏、保证金、强平价等状态，
 * 在内存中计算与展示使用，不直接持久化。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioStatus {

    /** 💰 总资产价值（total equity） */
    private Double totalValue;

    /** 💵 可用现金余额（available balance） */
    private Double cash;

    /** 📊 当前持仓数量（position size） */
    private Double position;

    /** 📈 当前盈亏百分比（保证金收益率%） */
    private Double pnLPercent;

    /** 💱 当前交易对（symbol，例如USDT） */
    private String symbol;

    /** 🕒 更新时间（快照生成时间） */
    private LocalDateTime updateTime;

    /** 🧭 当前方向（多头 / 空头 / NONE） */
    private String direction;

    /** 🎯 开仓均价（avg entry price） */
    private Double entryPrice;

    /** 💹 标记价格（Bybit Mark Price） */
    private Double markPrice;

    /** 🧮 当前占用保证金（margin used） */
    private Double marginUsed;

    /** 📉 未实现盈亏（USDT金额） */
    private Double unrealisedPnL;

    /** ⚠️ 强平价格（liquidation price） */
    private Double liquidationPrice;

}
