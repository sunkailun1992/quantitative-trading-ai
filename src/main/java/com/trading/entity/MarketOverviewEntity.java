package com.trading.entity; // 所在包名

import jakarta.persistence.*; // JPA 注解
import lombok.*; // Lombok 注解
import org.hibernate.annotations.Comment; // Hibernate 的实体注释
import java.time.LocalDateTime; // 时间类型

/**
 * 🧠 MarketOverviewEntity
 * 大行情分析 —— 每条记录是一份行情分析文本
 */
@Entity // 声明为 JPA 实体
@Table(
        name = "market_overview", // 表名
        indexes = {
                @Index(name = "idx_created_at", columnList = "created_at"),              // 按时间的索引（已有）
                @Index(name = "idx_author_created_at", columnList = "author, created_at") // 新增：作者 + 时间 联合索引，支持查每个作者的最新一条
        }
)
@Data // Lombok 自动生成 getter/setter/toString 等
@NoArgsConstructor // 无参构造
@AllArgsConstructor // 全参构造
@Builder // Builder 模式
@Comment("每日大行情分析，仅包含作者、内容与时间") // 表的注释
public class MarketOverviewEntity {

    /** 主键ID */
    @Id // 主键
    @GeneratedValue(strategy = GenerationType.IDENTITY) // 自增策略
    @Column(columnDefinition = "BIGINT COMMENT '主键ID'") // 列定义 + 注释
    private Long id; // 主键字段

    /** 作者（分析来源） */
    @Column(length = 100, columnDefinition = "VARCHAR(100) COMMENT '分析作者'") // 作者列定义
    private String author; // 作者名称（如：小助理、柳玉东）

    /** 分析内容（大段文字） */
    @Column(columnDefinition = "LONGTEXT COMMENT '详细分析内容'") // LONGTEXT 存放长文本
    private String fullAnalysis; // 大行情详细分析内容

    /** 创建时间（日期） */
    @Column(name = "created_at", columnDefinition = "DATETIME COMMENT '创建时间'") // 创建时间列定义
    private LocalDateTime createdAt = LocalDateTime.now(); // 默认值为当前时间
}
