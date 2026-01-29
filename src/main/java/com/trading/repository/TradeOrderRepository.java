package com.trading.repository;

import com.trading.entity.TradeOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 💹 TradeOrderRepository
 * 交易订单记录表的访问接口
 */
@Repository
public interface TradeOrderRepository extends JpaRepository<TradeOrderEntity, Long> {

    /**
     * 查询某交易对最近的订单（按时间倒序）
     */
    List<TradeOrderEntity> findTop20BySymbolOrderByCreatedAtDesc(String symbol);


    /**
     * ✅ 时间区间 + 交易对过滤（工程级推荐）
     */
    List<TradeOrderEntity> findBySymbolAndCreatedAtBetweenOrderByCreatedAtDesc(
            String symbol,
            LocalDateTime start,
            LocalDateTime end
    );

    /**
     * ✅ 查询某交易对最新未平仓订单（closed = false 或 null）
     */
    Optional<TradeOrderEntity> findTop1BySymbolAndClosedFalseOrderByCreatedAtDesc(String symbol);
}
