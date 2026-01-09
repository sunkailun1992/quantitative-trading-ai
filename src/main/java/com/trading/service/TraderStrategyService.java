package com.trading.service;                                                      // 包名

import com.trading.entity.TraderStrategyEntity;                                   // 实体类
import com.trading.repository.TraderStrategyRepository;                           // 仓库
import lombok.RequiredArgsConstructor;                                            // 构造器注入
import lombok.extern.slf4j.Slf4j;                                                 // 日志
import org.springframework.stereotype.Service;                                    // 服务注解

import java.time.LocalDate;                                                       // 日期
import java.time.LocalDateTime;                                                   // 日期时间
import java.time.LocalTime;                                                       // 时间
import java.util.List;                                                            // 列表

/**
 * ⚙️ TraderStrategyService
 * 新增“只取当天策略”的封装方法
 */
@Service                                                                          // 标记为服务组件
@RequiredArgsConstructor                                                           // 生成包含final字段的构造器
@Slf4j                                                                             // 开启日志
public class TraderStrategyService {

    private final TraderStrategyRepository repository;                            // 注入策略仓库

    /**
     * 取“当天 + 指定symbol”的所有交易员策略
     * @param symbol 交易对（如 BTCUSDT）
     * @return 当天的策略列表
     */
    public List<TraderStrategyEntity> getTodayStrategiesBySymbol(String symbol) { // 对外方法
        LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();        // 当天00:00
        LocalDateTime endOfDay = startOfDay.plusDays(1).plusHours(8);                       // 明天8:00
        log.debug("📅 查询当天策略: symbol={}, [{} ~ {}]",                                    // 记录查询范围
                symbol, startOfDay, endOfDay);                                              // 日志参数
        return repository.findBySymbolAndCreatedAtBetween(                                  // 调用仓库方法
                symbol, startOfDay, endOfDay                                                // 传入时间范围
        );
    }
}
