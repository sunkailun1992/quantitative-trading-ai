package com.trading.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 💰 WalletSnapshotEntity
 * 钱包余额快照表（记录账户总资产与可用余额变化）
 */
@Entity
@Comment("钱包余额快照表 - 记录账户总资产与可用余额变化")
@Table(name = "wallet_snapshot",
        indexes = {
                @Index(name = "idx_created_at", columnList = "created_at")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WalletSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "BIGINT COMMENT '主键ID'")
    private Long id;

    @Column(name = "total_equity", precision = 18, scale = 8, nullable = false, columnDefinition = "DECIMAL(18,8) COMMENT '总资产'")
    private BigDecimal totalEquity;

    @Column(name = "available_balance", precision = 18, scale = 8, nullable = false, columnDefinition = "DECIMAL(18,8) COMMENT '可用余额'")
    private BigDecimal availableBalance;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME COMMENT '记录创建时间'")
    private LocalDateTime createdAt = LocalDateTime.now();
}
