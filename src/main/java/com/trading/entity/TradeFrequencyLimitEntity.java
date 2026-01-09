package com.trading.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 📊 TradeFrequencyLimitEntity
 * 记录每日交易频率，用于防止AI高频交易
 */
@Entity // 声明为JPA实体类
@Comment("记录每日交易频率，用于防止AI高频交易")
@Table(name = "trade_frequency_limit") // 指定表名
@Data // Lombok自动生成getter/setter
@NoArgsConstructor // 无参构造函数
public class TradeFrequencyLimitEntity {

    @Id // 主键标识
    @GeneratedValue(strategy = GenerationType.IDENTITY) // 自增主键
    @Column(name = "id")
    private Long id; // 主键ID

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol; // 交易对，如BTCUSDT

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate; // 交易日期（按天统计）

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt; // 创建时间（记录交易时刻）
}
