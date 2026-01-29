package com.trading.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;

import java.time.LocalDateTime;

/**
 * 📈 MarketKlineEntity
 * 数据库实体：保存 Bybit 的 15分钟 K线数据。
 * 每条记录代表一根15分钟K线，包括开高低收和成交量。
 */
@Data
@Entity
@Comment("Bybit 15分钟K线数据表，用于保存市场价格序列")
@Table(
        name = "market_kline_15m", // 表名
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"symbol", "open_time"}) // 唯一约束：同一交易对在同一时间只存在一根K线
        },
        indexes = {
                @Index(name = "idx_symbol_time", columnList = "symbol, open_time") // 性能优化索引
        }
)
public class MarketKlineEntity {

    /** 主键ID（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键ID")
    private Long id;

    /** 交易对 */
    @Column(nullable = false, length = 20)
    @Comment("交易对符号")
    private String symbol;

    /** K线周期（分钟） */
    @Column(name = "interval_min", nullable = false)
    @Comment("K线周期长度（单位：分钟），默认15分钟")
    private Integer intervalMin;

    /** K线开盘时间（对应Bybit时间戳转本地时间） */
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

    /** 数据插入时间（默认当前时间） */
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME")
    @Comment("记录插入时间")
    private LocalDateTime createdAt = LocalDateTime.now();
}
