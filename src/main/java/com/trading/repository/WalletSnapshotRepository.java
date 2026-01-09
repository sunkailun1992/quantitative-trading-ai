package com.trading.repository;

import com.trading.entity.WalletSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 💰 WalletSnapshotRepository
 * 用于记录钱包资产变化历史
 */
@Repository
public interface WalletSnapshotRepository extends JpaRepository<WalletSnapshotEntity, Long> {

    /**
     * 查询最近一条快照（用于比较账户增长）
     */
    WalletSnapshotEntity findTop1ByOrderByCreatedAtDesc();
}
