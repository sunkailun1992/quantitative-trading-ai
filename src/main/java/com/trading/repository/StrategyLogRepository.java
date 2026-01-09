package com.trading.repository;

import com.trading.entity.StrategyLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * 🧠 StrategyLogRepository
 * 用于访问策略执行日志表（strategy_log）
 */
@Repository
public interface StrategyLogRepository extends JpaRepository<StrategyLogEntity, Long> {

    /**
     * 查询最近的 N 条策略日志（按时间倒序）
     */
    List<StrategyLogEntity> findTop50ByOrderByCreatedAtDesc();

    /**
     * 根据日志级别（INFO/WARN/ERROR）筛选日志
     */
    List<StrategyLogEntity> findByLevelOrderByCreatedAtDesc(String level);
}
