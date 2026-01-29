package com.trading.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;

import java.time.LocalDateTime;

/**
 * ⏱️ MarketKline1hEntity
 * 数据库实体：保存 Bybit 的 1小时 K线数据
 */
@Data
@Entity
@Comment("Bybit 1小时K线数据表，用于保存小时级市场行情")
@Table(
        name = "market_kline_1h",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"symbol", "open_time"})
        },
        indexes = {
                @Index(name = "idx_symbol_time_1h", columnList = "symbol, open_time")
        }
)
public class MarketKline1hEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键ID")
    private Long id;

    @Column(nullable = false, length = 20)
    @Comment("交易对符号")
    private String symbol;

    @Column(name = "interval_min", nullable = false)
    @Comment("K线周期长度（单位：分钟），固定为60")
    private Integer intervalMin = 60;

    @Column(name = "open_time", nullable = false, columnDefinition = "DATETIME")
    @Comment("K线开盘时间")
    private LocalDateTime openTime;

    @Column(name = "open", columnDefinition = "DOUBLE")
    @Comment("开盘价")
    private Double open;

    @Column(name = "high", columnDefinition = "DOUBLE")
    @Comment("最高价")
    private Double high;

    @Column(name = "low", columnDefinition = "DOUBLE")
    @Comment("最低价")
    private Double low;

    @Column(name = "close", columnDefinition = "DOUBLE")
    @Comment("收盘价")
    private Double close;

    @Column(name = "volume", columnDefinition = "DOUBLE")
    @Comment("成交量")
    private Double volume;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME")
    @Comment("记录插入时间")
    private LocalDateTime createdAt = LocalDateTime.now();
}
