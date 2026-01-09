package com.trading.repository;

import com.trading.entity.MarketKlineDailyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 🧩 日K仓库
 * 用于操作日K线数据
 */
@Repository
public interface MarketKlineDailyRepository extends JpaRepository<MarketKlineDailyEntity, Long> {

    /** 按时间区间查询指定symbol的日K线（升序） */
    List<MarketKlineDailyEntity> findBySymbolAndOpenTimeBetweenOrderByOpenTimeAsc(
            String symbol,
            LocalDateTime start,
            LocalDateTime end
    );

    /** 判断是否存在重复记录 */
    boolean existsBySymbolAndOpenTime(String symbol, LocalDateTime openTime);

    /** ✅ 新增：查询全量日K线（升序） */
    List<MarketKlineDailyEntity> findBySymbolOrderByOpenTimeAsc(String symbol);
}
