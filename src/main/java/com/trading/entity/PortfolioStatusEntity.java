package com.trading.entity;

import com.trading.model.PortfolioStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 💾 PortfolioStatusEntity
 * 投资组合状态数据库实体类
 * ---------------------------------------------------------
 * 用于保存账户资产快照（含持仓、盈亏、保证金、强平价等）。
 */
@Entity
@Table(
        name = "portfolio_status",
        indexes = {
                @Index(name = "idx_symbol", columnList = "symbol"),
                @Index(name = "idx_created_at", columnList = "created_at")
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortfolioStatusEntity {

    /** 🔑 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false,
            columnDefinition = "BIGINT COMMENT '主键ID'")
    private Long id;

    /** 💱 交易对（symbol，例如 USDT） */
    @Column(name = "symbol", nullable = false, length = 20,
            columnDefinition = "VARCHAR(20) COMMENT '交易对（symbol，例如USDT）'")
    private String symbol;

    /** 💰 当前账户总资产（total equity） */
    @Column(name = "total_value", precision = 18, scale = 8, nullable = false,
            columnDefinition = "DECIMAL(18,8) COMMENT '账户总资产（totalEquity）'")
    private BigDecimal totalValue;

    /** 💵 可用余额（available balance） */
    @Column(name = "cash", precision = 18, scale = 8, nullable = false,
            columnDefinition = "DECIMAL(18,8) COMMENT '可用余额（availableBalance）'")
    private BigDecimal cash;

    /** 📊 当前持仓数量（position size） */
    @Column(name = "position", precision = 18, scale = 8, nullable = false,
            columnDefinition = "DECIMAL(18,8) COMMENT '当前持仓数量（position size）'")
    private BigDecimal position;

    /** 📈 当前盈亏百分比（保证金收益率%） */
    @Column(name = "pnl_percent", precision = 10, scale = 6, nullable = false,
            columnDefinition = "DECIMAL(10,6) COMMENT '当前盈亏百分比（保证金收益率口径）'")
    private BigDecimal pnlPercent;

    /** 🧭 当前方向（多头 / 空头 / NONE） */
    @Column(name = "direction", length = 10,
            columnDefinition = "VARCHAR(10) COMMENT '当前持仓方向（多头 / 空头 / NONE）'")
    private String direction;

    /** 🎯 当前开仓均价 */
    @Column(name = "entry_price", precision = 18, scale = 8,
            columnDefinition = "DECIMAL(18,8) COMMENT '当前开仓均价'")
    private BigDecimal entryPrice;

    /** 💹 标记价格（Mark Price） */
    @Column(name = "mark_price", precision = 18, scale = 8,
            columnDefinition = "DECIMAL(18,8) COMMENT 'Bybit标记价格（markPrice）'")
    private BigDecimal markPrice;

    /** 🧮 当前占用保证金（Margin Used） */
    @Column(name = "margin_used", precision = 18, scale = 8,
            columnDefinition = "DECIMAL(18,8) COMMENT '占用保证金（marginUsed）'")
    private BigDecimal marginUsed;

    /** 📉 当前未实现盈亏（USDT金额） */
    @Column(name = "unrealised_pnl", precision = 18, scale = 8,
            columnDefinition = "DECIMAL(18,8) COMMENT '未实现盈亏（USDT金额）'")
    private BigDecimal unrealisedPnL;

    /** ⚠️ 强平价格（Liquidation Price） */
    @Column(name = "liquidation_price", precision = 18, scale = 8,
            columnDefinition = "DECIMAL(18,8) COMMENT '强平价格（liquidationPrice）'")
    private BigDecimal liquidationPrice;

    /** 🕒 创建时间（快照生成时间） */
    @Column(name = "created_at", nullable = false,
            columnDefinition = "DATETIME COMMENT '记录创建时间'")
    private LocalDateTime createdAt = LocalDateTime.now();

    // ========== 🧩 构造方法：从模型对象创建 ==========
    public PortfolioStatusEntity(PortfolioStatus s) {
        this.symbol = s.getSymbol();
        this.totalValue = BigDecimal.valueOf(s.getTotalValue());
        this.cash = BigDecimal.valueOf(s.getCash());
        this.position = BigDecimal.valueOf(s.getPosition());
        this.pnlPercent = BigDecimal.valueOf(s.getPnLPercent());
        this.direction = s.getDirection();
        this.entryPrice = BigDecimal.valueOf(s.getEntryPrice());
        this.markPrice = s.getMarkPrice() != null ? BigDecimal.valueOf(s.getMarkPrice()) : BigDecimal.ZERO;
        this.marginUsed = s.getMarginUsed() != null ? BigDecimal.valueOf(s.getMarginUsed()) : BigDecimal.ZERO;
        this.unrealisedPnL = s.getUnrealisedPnL() != null ? BigDecimal.valueOf(s.getUnrealisedPnL()) : BigDecimal.ZERO;
        this.liquidationPrice = s.getLiquidationPrice() != null ? BigDecimal.valueOf(s.getLiquidationPrice()) : BigDecimal.ZERO;
        this.createdAt = LocalDateTime.now();
    }
}
