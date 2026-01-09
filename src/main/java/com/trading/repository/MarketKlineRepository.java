package com.trading.repository;

import com.trading.entity.MarketKlineEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 🧩 MarketKlineRepository
 * 提供数据库操作接口：检测重复并写入15分钟K线数据
 */
@Repository
public interface MarketKlineRepository extends JpaRepository<MarketKlineEntity, Long> {

    /** 🕒 根据交易对和时间区间查询15分钟K线（按时间升序） */
    List<MarketKlineEntity> findBySymbolAndOpenTimeBetweenOrderByOpenTimeAsc(
            String symbol,
            LocalDateTime start,
            LocalDateTime end
    );

    /** ⚠️ 检查是否存在相同 symbol + openTime 的记录（防止重复） */
    boolean existsBySymbolAndOpenTime(String symbol, LocalDateTime openTime);

    /** ✅ 新增：按交易对获取全部15分钟K线（升序）—— 用于长周期EMA */
    List<MarketKlineEntity> findBySymbolOrderByOpenTimeAsc(String symbol);
}
