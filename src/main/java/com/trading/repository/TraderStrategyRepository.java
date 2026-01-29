package com.trading.repository;

import com.trading.entity.TraderStrategyEntity;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 🧩 TraderStrategyRepository
 * 提供数据库操作接口，用于保存和查询交易员策略
 */
@Repository
public interface TraderStrategyRepository extends JpaRepository<TraderStrategyEntity, Long> {

    /**
     * 根据交易员名称查询其所有策略
     */
    List<TraderStrategyEntity> findByTraderName(String traderName);

    /**
     * 根据交易对查询策略
     */
    List<TraderStrategyEntity> findBySymbol(String symbol);

    /**
     * ✅ 新增：根据交易对与时间范围查询策略（用于“当天策略”）
     * @param symbol 币种或交易对
     * @param startOfDay 当天开始时间
     * @param endOfDay 当天结束时间
     * @return 当天内的策略列表
     */
    List<TraderStrategyEntity> findBySymbolAndCreatedAtBetween(
            String symbol,
            LocalDateTime startOfDay,
            LocalDateTime endOfDay
    );


    /**
     * ✅ 修复版：删除指定交易员当天策略（显式事务 + @Modifying）
     * 防止在非事务上下文中报错 “No EntityManager with actual transaction available”
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM TraderStrategyEntity t WHERE t.traderName = :traderName AND t.createdAt BETWEEN :startOfDay AND :endOfDay")
    int deleteByTraderNameAndCreatedAtBetween(@Param("traderName") String traderName,
                                              @Param("startOfDay") LocalDateTime startOfDay,
                                              @Param("endOfDay") LocalDateTime endOfDay);
}
