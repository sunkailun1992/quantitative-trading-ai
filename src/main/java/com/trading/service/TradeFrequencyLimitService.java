package com.trading.service;

import com.trading.entity.TradeFrequencyLimitEntity;
import com.trading.repository.TradeFrequencyLimitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ⚙️ TradeFrequencyLimitService
 * 用于控制AI每日交易频率（默认每日最多3次）
 */
@Slf4j // 自动注入日志对象
@Service // 声明为服务层组件
@RequiredArgsConstructor // 自动生成构造函数注入
public class TradeFrequencyLimitService {

    private final TradeFrequencyLimitRepository repository; // 注入仓库对象

    /**
     * 获取指定交易对当天的交易次数
     */
    public int getTradeCount(String symbol, LocalDate date) {
        // 调用仓库统计
        int count = repository.countBySymbolAndTradeDate(symbol, date);
        log.debug("📊 当前交易对 {} 当日交易次数: {}", symbol, count); // 打印调试日志
        return count; // 返回计数
    }

    /**
     * 成功下单后调用，记录一次交易
     */
    @Transactional // 确保数据库事务一致性
    public void incrementTradeCount(String symbol) {
        // 创建记录对象
        TradeFrequencyLimitEntity entity = new TradeFrequencyLimitEntity();
        entity.setSymbol(symbol); // 设置交易对
        entity.setTradeDate(LocalDate.now()); // 设置交易日期
        entity.setCreatedAt(LocalDateTime.now()); // 设置创建时间

        repository.save(entity); // 保存数据库记录

        log.info("✅ 成功记录交易频率: {} @ {}", symbol, entity.getTradeDate()); // 打印成功日志
    }
}
