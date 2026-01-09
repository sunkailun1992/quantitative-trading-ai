package com.trading.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;

import java.time.LocalDateTime;

/**
 * 📆 MarketKlineWeeklyEntity
 * 数据库实体：保存 Bybit 的 周K线（1W）数据。
 * 每条记录代表一根周K线，包括开盘价、最高价、最低价、收盘价、成交量。
 */
@Data
@Entity
@Comment("Bybit 周K线数据表，用于保存每周行情走势（1W）")
@Table(
        name = "market_kline_1w", // 表名
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"symbol", "open_time"}) // 唯一约束：同symbol+开盘时间
        },
        indexes = {
                @Index(name = "idx_symbol_time_1w", columnList = "symbol, open_time") // 提升查询性能
        }
)
public class MarketKlineWeeklyEntity {

    /** 主键ID（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键ID")
    private Long id;

    /** 交易对，如 BTCUSDT */
    @Column(nullable = false, length = 20)
    @Comment("交易对符号，例如 BTCUSDT")
    private String symbol;

    /** K线周期（分钟），周K为 10080 分钟 */
    @Column(name = "interval_min", nullable = false)
    @Comment("K线周期长度（单位：分钟），周K固定为10080")
    private Integer intervalMin = 10080;

    /** K线开盘时间 */
    @Column(name = "open_time", nullable = false, columnDefinition = "DATETIME")
    @Comment("K线开盘时间")
    private LocalDateTime openTime;

    /** 开盘价 */
    @Column(name = "open", columnDefinition = "DOUBLE")
    @Comment("开盘价")
    private Double open;

    /** 最高价 */
    @Column(name = "high", columnDefinition = "DOUBLE")
    @Comment("最高价")
    private Double high;

    /** 最低价 */
    @Column(name = "low", columnDefinition = "DOUBLE")
    @Comment("最低价")
    private Double low;

    /** 收盘价 */
    @Column(name = "close", columnDefinition = "DOUBLE")
    @Comment("收盘价")
    private Double close;

    /** 成交量 */
    @Column(name = "volume", columnDefinition = "DOUBLE")
    @Comment("成交量")
    private Double volume;

    /** 数据插入时间 */
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME")
    @Comment("记录插入时间（系统生成）")
    private LocalDateTime createdAt = LocalDateTime.now();
}
