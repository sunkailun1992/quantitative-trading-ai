package com.trading.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;

import java.time.LocalDateTime;

/**
 * 📅 MarketKlineDailyEntity
 * 数据库实体：保存 Bybit 的 日K线（1D）数据。
 * 每条记录代表一根日K线，包括开盘价、最高价、最低价、收盘价与成交量。
 */
@Data
@Entity
@Comment("Bybit 日K线数据表，用于保存每日行情走势（1D）")
@Table(
        name = "market_kline_1d", // 表名
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"symbol", "open_time"}) // 保证同symbol+时间唯一
        },
        indexes = {
                @Index(name = "idx_symbol_time_1d", columnList = "symbol, open_time") // 提高查询性能
        }
)
public class MarketKlineDailyEntity {

    /** 主键ID（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键ID")
    private Long id;

    /** 交易对，如 BTCUSDT */
    @Column(nullable = false, length = 20)
    @Comment("交易对符号，例如 BTCUSDT")
    private String symbol;

    /** K线周期（分钟） - 日K为1440分钟 */
    @Column(name = "interval_min", nullable = false)
    @Comment("K线周期长度（单位：分钟），日K固定为1440")
    private Integer intervalMin = 1440;

    /** K线开盘时间（对应Bybit时间戳转本地时间） */
    @Column(name = "open_time", nullable = false, columnDefinition = "DATETIME")
    @Comment("K线开盘时间（本地时间）")
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

    /** 数据插入时间（默认当前时间） */
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME")
    @Comment("记录插入时间（系统生成）")
    private LocalDateTime createdAt = LocalDateTime.now();
}
