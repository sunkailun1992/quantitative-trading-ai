package com.trading.repository;

import com.trading.entity.AiStrategyRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * 🧠 AI 策略记录仓库
 * 用于保存AI策略执行历史
 */
@Repository
public interface AiStrategyRecordRepository extends JpaRepository<AiStrategyRecordEntity, Long> {

    /**
     * 查询指定策略最近执行的记录
     */
    List<AiStrategyRecordEntity> findTop20ByStrategyNameOrderByCreatedAtDesc(String strategyName);
}
