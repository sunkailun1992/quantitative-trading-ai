package com.trading.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 🚀 TradeOrderEntity
 * 交易订单记录表 —— 记录每一次 AI 交易（开仓 / 平仓）
 */
@Entity
@Comment("交易订单记录表 - 保存每笔下单与成交信息（含盈亏、方向、杠杆、开仓均价等）")
@Table(name = "trade_order",
        indexes = {
                @Index(name = "idx_symbol", columnList = "symbol"),
                @Index(name = "idx_created_at", columnList = "created_at")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeOrderEntity {

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "BIGINT COMMENT '主键ID'")
    private Long id;

    /** Bybit订单ID */
    @Column(name = "order_id", length = 64, columnDefinition = "VARCHAR(64) COMMENT 'Bybit订单ID'")
    private String orderId;

    /** 交易对 */
    @Column(name = "symbol", length = 20, nullable = false, columnDefinition = "VARCHAR(20) COMMENT '交易对，例如USDT'")
    private String symbol;

    /**
     * 订单方向（扩展支持）
     * - BUY：开多
     * - SELL：开空
     * - CLOSE_LONG：平多
     * - CLOSE_SHORT：平空
     */
    @Column(name = "side", length = 20, nullable = false, columnDefinition = "VARCHAR(20) COMMENT '买卖方向（BUY/SELL/CLOSE_LONG/CLOSE_SHORT）'")
    private String side;

    /** 下单数量 */
    @Column(name = "qty", precision = 18, scale = 8, nullable = false, columnDefinition = "DECIMAL(18,8) COMMENT '下单数量'")
    private BigDecimal qty;

    /** 成交价格 */
    @Column(name = "price", precision = 18, scale = 8, columnDefinition = "DECIMAL(18,8) COMMENT '成交价格'")
    private BigDecimal price;

    /** 🧾 开仓均价 */
    @Column(name = "avg_entry_price", precision = 18, scale = 8, columnDefinition = "DECIMAL(18,8) COMMENT '开仓均价'")
    private BigDecimal avgEntryPrice;

    /** 杠杆倍数 */
    @Column(name = "leverage", precision = 5, scale = 2, columnDefinition = "DECIMAL(5,2) COMMENT '杠杆倍数'")
    private BigDecimal leverage;

    /** 盈亏百分比（保证金收益率%） */
    @Column(name = "pnl_percent", precision = 10, scale = 6, columnDefinition = "DECIMAL(10,6) COMMENT '盈亏百分比（保证金收益率%）'")
    private BigDecimal pnlPercent;

    /** 订单状态 */
    @Column(name = "status", length = 20, columnDefinition = "VARCHAR(20) COMMENT '订单状态（FILLED/CANCELLED/PENDING）'")
    private String status;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME COMMENT '创建时间'")
    private LocalDateTime createdAt = LocalDateTime.now();

    /** AI开仓理由 */
    @Column(name = "comment", columnDefinition = "TEXT COMMENT 'AI开仓理由'")
    private String comment;

    // ==================== 🆕 新增字段 ====================

    /** 是否平仓（true=已平仓, false=未平仓） */
    @Column(name = "closed", nullable = false, columnDefinition = "TINYINT(1) DEFAULT 0 COMMENT '是否平仓（1=已平仓, 0=未平仓）'")
    private Boolean closed = false;

    /** 平仓订单ID（若当前记录为开仓单，则为空；若为平仓单，则存放对应开仓单ID） */
    @Column(name = "close_order_id", length = 64, columnDefinition = "VARCHAR(64) COMMENT '平仓对应的开仓订单ID'")
    private String closeOrderId;

    /** 平仓价格（仅在平仓记录中有效，表示本次平仓获得的实际价格） */
    @Column(name = "close_amount", precision = 18, scale = 8, columnDefinition = "DECIMAL(18,8) COMMENT '平仓价格'")
    private BigDecimal closeAmount;

    /** AI平仓理由 */
    @Column(name = "close_comment", columnDefinition = "TEXT COMMENT 'AI平仓理由'")
    private String closeComment;

    // ==================== 📈 扩展交易监控字段 ====================

    /** 🧮 当前占用保证金（Margin Used） */
    @Column(name = "margin_used", precision = 18, scale = 8, columnDefinition = "DECIMAL(18,8) COMMENT '当前占用保证金'")
    private BigDecimal marginUsed;

    /** 📉 未实现盈亏（Unrealised PnL，USDT金额） */
    @Column(name = "unrealised_pnl", precision = 18, scale = 8, columnDefinition = "DECIMAL(18,8) COMMENT '未实现盈亏（USDT金额）'")
    private BigDecimal unrealisedPnL;

    /** ⚠️ 强平价格（Liquidation Price） */
    @Column(name = "liquidation_price", precision = 18, scale = 8, columnDefinition = "DECIMAL(18,8) COMMENT '强平价格'")
    private BigDecimal liquidationPrice;
}
