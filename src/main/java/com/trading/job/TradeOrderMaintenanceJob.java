// 每行都有注释（按你的要求）

package com.trading.job;                                              // 指定当前类所在包名

import com.trading.entity.TradeOrderEntity;                           // 引入实体类
import com.trading.repository.TradeOrderRepository;                   // 引入仓库接口
import lombok.RequiredArgsConstructor;                                 // 引入 Lombok 注解自动生成构造器
import lombok.extern.slf4j.Slf4j;                                      // 引入日志注解
import org.springframework.scheduling.annotation.Scheduled;           // 引入 @Scheduled 注解
import org.springframework.stereotype.Service;                         // 引入 @Service 注解
import org.springframework.transaction.annotation.Transactional;       // 引入 @Transactional 注解

import java.time.*;                                                    // 引入时间相关类（ZoneId、LocalDate、LocalDateTime）
import java.util.List;                                                // 引入 List

@Slf4j                                                                 // 启用日志记录（log.info 等）
@Service                                                               // 标记为 Spring 服务组件
@RequiredArgsConstructor                                               // 自动注入 final 字段（构造注入）
public class TradeOrderMaintenanceJob {                                // 定义定时任务类

    private final TradeOrderRepository tradeOrderRepository;           // 仓库依赖（通过构造器注入）

    // 每天 02:00 执行；Spring Cron 为 6 段格式（秒 分 时 日 月 周）                 // 说明 Cron 表达式
    // zone = "Asia/Tokyo" 保证按日本时区触发（你现在在日本）                         // 指定时区很重要
    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Hong_Kong")           // 每天凌晨 02:00（香港时间）执行
    @Transactional                                                      // 事务包裹：批量更新要么全部成功要么全部回滚
    public void normalizeStaleOpenOrdersCreatedAt() {                   // 定义任务方法：规范化未平仓历史单的 createdAt

        ZoneId hongKong = ZoneId.of("Asia/Hong_Kong");         // 香港时区
        LocalDate todayHK = LocalDate.now(hongKong);           // 获取今天（香港日期）
        LocalDateTime todayStartHK = todayHK.atStartOfDay();   // 今天 00:00:00

        log.info("【定时任务】开始规范历史未平仓订单的创建时间，cutoff={} (Asia/Tokyo)", todayStartHK); // 打印任务开始日志

        List<TradeOrderEntity> staleOpenOrders =                       // 定义列表接收查询结果
                tradeOrderRepository.findStaleOpenOrders(todayStartHK); // 查询“未平仓且 createdAt < 今天”的订单

        log.info("【定时任务】查询到历史未平仓订单数量：{}", staleOpenOrders.size()); // 打印查询数量

        if (staleOpenOrders.isEmpty()) {                               // 如果没有需要处理的订单
            log.info("【定时任务】无历史未平仓订单需要处理，任务结束。");            // 打印结束日志
            return;                                                    // 提前返回结束任务
        }                                                              // if 结束

        LocalDateTime nowTokyo = LocalDateTime.now(hongKong);             // 获取东京时区的当前时间（作为新的 createdAt）

        staleOpenOrders.forEach(order -> {                             // 遍历每一条需要处理的订单
            LocalDateTime oldCreatedAt = order.getCreatedAt();         // 取出老的创建时间用于日志
            order.setCreatedAt(nowTokyo);                              // 把 createdAt 改为“今天的当前时刻”
            log.info("【定时任务】订单ID={} createdAt: {} -> {}",        // 逐条打印变更明细
                    order.getId(), oldCreatedAt, nowTokyo);            // 日志参数：订单ID、旧时间、新时间
        });                                                            // 遍历结束

        tradeOrderRepository.saveAll(staleOpenOrders);                 // 批量保存更新后的订单记录

        log.info("【定时任务】历史未平仓订单更新时间完成，实际更新条数：{}", staleOpenOrders.size()); // 打印完成日志
    }                                                                  // 方法结束
}                                                                      // 类结束
