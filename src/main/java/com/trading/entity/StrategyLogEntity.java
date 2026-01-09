package com.trading.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import java.time.LocalDateTime;

/**
 * ⚙️ StrategyLogEntity
 * 策略执行日志表
 */
@Entity
@Comment("策略执行日志表 - 记录策略运行、触发信号与异常信息")
@Table(name = "strategy_log",
        indexes = {
                @Index(name = "idx_level", columnList = "level"),
                @Index(name = "idx_created_at", columnList = "created_at")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StrategyLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "BIGINT COMMENT '主键ID'")
    private Long id;

    @Column(name = "level", length = 10, nullable = false, columnDefinition = "VARCHAR(10) COMMENT '日志级别（INFO/WARN/ERROR）'")
    private String level;

    @Column(name = "message", length = 1000, nullable = false, columnDefinition = "VARCHAR(1000) COMMENT '日志内容'")
    private String message;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME COMMENT '记录时间'")
    private LocalDateTime createdAt = LocalDateTime.now();
}
