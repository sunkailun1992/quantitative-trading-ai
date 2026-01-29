package com.trading.entity;

import jakarta.persistence.*;                 // JPA 注解：@Entity、@Table、@Id、@Column 等
import lombok.AllArgsConstructor;            // Lombok：全参构造
import lombok.Builder;                       // Lombok：Builder 模式
import lombok.Data;                          // Lombok：getter/setter/toString/hashCode/equals
import lombok.NoArgsConstructor;             // Lombok：无参构造
import org.hibernate.annotations.Comment;    // Hibernate 6：表/列注释（会生成 MySQL COMMENT）
import java.math.BigDecimal;                 // 金融金额类型，避免 double 精度误差
import java.time.LocalDateTime;              // 记录时间

/**
 * 💾 AiStrategyRecordEntity
 * AI 策略执行记录表 —— 用于落库 AI 生成的交易建议（原始/校准/执行等状态），便于复盘与统计。
 *
 * ⚠️ 注意：
 * 1) MySQL 中 signal 是保留字，因此列名使用 trade_signal 避免语法冲突；
 * 2) 金额/数量/信心等使用 BigDecimal，配合 precision + scale；
 * 3) Hibernate 6 使用 @Comment 生成表/列注释；
 * 4) Spring Boot 3 默认 Hibernate 6，确保 application.yml 配置了 MySQL8Dialect；
 *
 * 参考配置：
 * spring:
 *   jpa:
 *     hibernate:
 *       ddl-auto: update
 *     properties:
 *       hibernate:
 *         dialect: org.hibernate.dialect.MySQL8Dialect
 *         use_sql_comments: true
 *         format_sql: true
 *         show_sql: true
 */
@Entity
@Comment("AI 策略执行记录表 - 记录AI生成的交易建议、触发条件与执行状态")
@Table(
        name = "ai_strategy_record",
        indexes = {
                @Index(name = "idx_strategy_name", columnList = "strategy_name"),
                @Index(name = "idx_created_at", columnList = "created_at")
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiStrategyRecordEntity {

    /** 主键ID（自增） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "BIGINT COMMENT '主键ID'")
    @Comment("主键ID")
    private Long id;

    /** 策略名称（例如：DeepSeek-RSI-Strategy / AI-Core-Auto 等） */
    @Column(name = "strategy_name", nullable = false, length = 100,
            columnDefinition = "VARCHAR(100) COMMENT '策略名称'")
    @Comment("策略名称")
    private String strategyName;

    /** 交易信号（BUY/SELL/HOLD/扩展文本） */
    @Column(name = "trade_signal", nullable = false, length = 255,
            columnDefinition = "VARCHAR(255) COMMENT 'AI交易信号（BUY/SELL/HOLD/扩展文本）'") // 列定义
    @Comment("AI交易信号（BUY/SELL/HOLD/扩展文本）") // 列注释
    private String signal;

    /** 触发条件说明（AI生成的完整条件文本） */
    @Lob // 长文本类型（映射为LONGTEXT）
    @Column(name = "condition_trigger", columnDefinition = "LONGTEXT COMMENT '触发条件说明（AI生成的完整条件文本）'") // 列定义
    @Comment("触发条件说明（AI生成的完整条件文本）") // 列注释
    private String conditionTrigger;

    /** 生成信号时的价格（例如USDT当前价） */
    @Column(name = "price", precision = 18, scale = 8,
            columnDefinition = "DECIMAL(18,8) COMMENT 'AI生成信号时的价格'") // 列定义
    @Comment("AI生成信号时的价格") // 列注释
    private BigDecimal price;

    /** AI建议仓位数量 */
    @Column(name = "suggested_qty", precision = 18, scale = 8,
            columnDefinition = "DECIMAL(18,8) COMMENT 'AI建议的仓位数量'") // 列定义
    @Comment("AI建议的仓位数量") // 列注释
    private BigDecimal suggestedQty;

    /** 固定下单数量（例如0.005 BTC） */
    @Column(name = "order_qty", precision = 18, scale = 8,
            columnDefinition = "DECIMAL(18,8) COMMENT '固定数量（base，比如0.005 BTC）'") // 列定义
    @Comment("固定数量（base，比如0.005 BTC）") // 列注释
    private BigDecimal orderQty;

    /** AI 置信度（0~1，四位小数） */
    @Column(name = "confidence", precision = 10, scale = 4, nullable = false,
            columnDefinition = "DECIMAL(10,4) COMMENT 'AI 置信度（0~1）'") // 列定义
    @Comment("AI 置信度（0~1）") // 列注释
    private BigDecimal confidence;

    /**
     * 执行状态：
     * - RAW_DECISION：原始AI建议已保存
     * - CALIBRATED：完成二次校准
     * - EXECUTED：已实际下单
     * - SKIPPED：因风控/信心不足跳过
     * - FAILED：执行失败或异常
     */
    @Column(name = "execution_status", length = 50,
            columnDefinition = "VARCHAR(50) COMMENT '执行状态：RAW_DECISION/CALIBRATED/EXECUTED/SKIPPED/FAILED'")
    @Comment("执行状态")
    private String executionStatus;

    /** 错误信息（完整异常或堆栈文本） */
    @Lob // 长文本类型
    @Column(name = "error_message", columnDefinition = "LONGTEXT COMMENT '错误信息（完整异常或堆栈文本）'") // 列定义
    @Comment("错误信息（完整异常或堆栈文本）") // 列注释
    private String errorMessage;

    /** 创建时间（记录生成时间） */
    @Column(name = "created_at", nullable = false,
            columnDefinition = "DATETIME COMMENT '创建时间'") // 列定义
    @Comment("创建时间") // 列注释
    private LocalDateTime createdAt = LocalDateTime.now();
}
