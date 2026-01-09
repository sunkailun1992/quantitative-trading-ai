package com.trading.repository;

import com.trading.entity.MarketKlineWeeklyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 🧩 周K仓库
 * 用于操作周K线数据
 */
@Repository
public interface MarketKlineWeeklyRepository extends JpaRepository<MarketKlineWeeklyEntity, Long> {

    /** 查询指定时间区间内的周K线（升序） */
    List<MarketKlineWeeklyEntity> findBySymbolAndOpenTimeBetweenOrderByOpenTimeAsc(
            String symbol,
            LocalDateTime start,
            LocalDateTime end
    );

    /** 检查是否存在重复记录 */
    boolean existsBySymbolAndOpenTime(String symbol, LocalDateTime openTime);

    /** ✅ 新增：查询全量周K线（升序） */
    List<MarketKlineWeeklyEntity> findBySymbolOrderByOpenTimeAsc(String symbol);
}
