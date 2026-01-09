package com.trading.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import java.time.LocalDateTime;

/**
 * 💾 TraderStrategyEntity
 * 交易员策略表 —— 保存各交易员的最新策略信号
 */
@Entity
@Table(
        name = "trader_strategy",
        indexes = {
                @Index(name = "idx_symbol", columnList = "symbol"),
                @Index(name = "idx_created_at", columnList = "created_at")
        }
)
@Comment("交易员策略表 - 保存各交易员的最新策略信号")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TraderStrategyEntity {

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "BIGINT COMMENT '主键ID'")
    @Comment("主键ID")
    private Long id;

    /** 交易员名称 */
    @Column(name = "trader_name", nullable = false, length = 50, columnDefinition = "VARCHAR(50) COMMENT '交易员名称'")
    @Comment("交易员名称")
    private String traderName;

    /** 币种或交易对（例如 BTCUSDT） */
    @Column(name = "symbol", nullable = false, length = 20, columnDefinition = "VARCHAR(20) COMMENT '交易币种或交易对'")
    @Comment("交易币种或交易对")
    private String symbol;

    /** 操作方向（多 / 空 / 震荡） */
    @Column(name = "direction", length = 10, columnDefinition = "VARCHAR(10) COMMENT '操作方向（多/空/震荡）'")
    @Comment("操作方向（多/空/震荡）")
    private String direction;

    /** 建仓区间或价格 */
    @Column(name = "entry_range", length = 50, columnDefinition = "VARCHAR(50) COMMENT '建仓区间或价格'")
    @Comment("建仓区间或价格")
    private String entryRange;

    /** 止损价格 */
    @Column(name = "stop_loss", length = 30, columnDefinition = "VARCHAR(30) COMMENT '止损价'")
    @Comment("止损价")
    private String stopLoss;

    /** 止盈区间 */
    @Column(name = "take_profit", length = 100, columnDefinition = "VARCHAR(100) COMMENT '止盈区间或目标价'")
    @Comment("止盈区间或目标价")
    private String takeProfit;

    /** 策略类型或风格（例如：短线 / 波段 / 趋势） */
    @Column(name = "style", length = 50, columnDefinition = "VARCHAR(50) COMMENT '策略类型或风格'")
    @Comment("策略类型或风格")
    private String style;

    /** 策略备注（对行情的描述） */
    @Column(name = "comment", length = 500, columnDefinition = "TEXT COMMENT '交易员观点或策略说明'")
    @Comment("交易员观点或策略说明")
    private String comment;

    /** 策略创建时间 */
    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME COMMENT '策略记录时间'")
    @Comment("策略记录时间")
    private LocalDateTime createdAt = LocalDateTime.now();
}
