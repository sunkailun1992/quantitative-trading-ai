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
     * 查询所有订单（按创建时间倒序）
     */
    List<TradeOrderEntity> findTop50ByOrderByCreatedAtDesc();

    /**
     * ✅ 查询某交易对最新未平仓订单（closed = false 或 null）
     */
    Optional<TradeOrderEntity> findTop1BySymbolAndClosedFalseOrderByCreatedAtDesc(String symbol);

    @Query("select t from TradeOrderEntity t " +                      // 使用 JPQL 自定义查询
            "where (t.closed = false or t.closed is null) " +          // 条件1：未平仓（false 或 NULL）
            "and t.createdAt < :cutoff")                               // 条件2：创建时间早于给定“今天零点”
    List<TradeOrderEntity> findStaleOpenOrders(                       // 方法名：查找“过期的未平仓订单”
                                                                      @Param("cutoff") LocalDateTime cutoff                      // 命名参数：cutoff 表示“今天 00:00:00”
    );                                                                // 方法结束
}
