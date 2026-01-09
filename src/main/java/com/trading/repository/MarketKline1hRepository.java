package com.trading.repository;

import com.trading.entity.MarketKline1hEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 🧩 MarketKline1hRepository
 * 操作 1小时 K 线数据库
 */
@Repository
public interface MarketKline1hRepository extends JpaRepository<MarketKline1hEntity, Long> {

    /** 查询指定时间区间的1小时K线（升序） */
    List<MarketKline1hEntity> findBySymbolAndOpenTimeBetweenOrderByOpenTimeAsc(
            String symbol,
            LocalDateTime start,
            LocalDateTime end
    );

    /** 判断是否存在相同 symbol + openTime 记录（防止重复） */
    boolean existsBySymbolAndOpenTime(String symbol, LocalDateTime openTime);

    /** ✅ 新增：查询指定symbol的所有1小时K线（升序）—— 用于EMA144/288计算 */
    List<MarketKline1hEntity> findBySymbolOrderByOpenTimeAsc(String symbol);
}
