package com.trading.repository;

import com.trading.entity.TradeFrequencyLimitEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/**
 * 🧩 TradeFrequencyLimitRepository
 * 提供数据库操作接口，用于统计每日交易次数
 */
@Repository // 标记为仓库组件
public interface TradeFrequencyLimitRepository extends JpaRepository<TradeFrequencyLimitEntity, Long> {

    /**
     * 统计某交易对在指定日期的交易次数
     * @param symbol 交易对
     * @param tradeDate 日期
     * @return 当日交易次数
     */
    int countBySymbolAndTradeDate(String symbol, LocalDate tradeDate);
}
