package com.trading.repository; // 包名

import com.trading.entity.MarketOverviewEntity; // 引入实体
import jakarta.transaction.Transactional; // 事务注解
import org.springframework.data.jpa.repository.JpaRepository; // JPA 仓库基类
import org.springframework.data.jpa.repository.Modifying; // 修改操作注解
import org.springframework.data.jpa.repository.Query; // 自定义查询注解
import org.springframework.data.repository.query.Param; // 命名参数注解
import org.springframework.stereotype.Repository; // 仓库注解

import java.time.LocalDateTime; // 时间类型
import java.util.List; // 集合类型

/**
 * 🧩 MarketOverviewRepository
 * 用于访问大行情分析数据
 */
@Repository // 声明为 Spring Repository
public interface MarketOverviewRepository extends JpaRepository<MarketOverviewEntity, Long> { // 继承 JpaRepository，主键类型为 Long

    /**
     * 📅 按时间区间查询分析记录（旧逻辑保留，不用了也可以删）
     */
    List<MarketOverviewEntity> findByCreatedAtBetween(LocalDateTime startOfDay, LocalDateTime endOfDay); // 根据时间范围查询

    /**
     * 🗑 删除指定作者在某时间区间内的分析记录（保留原有逻辑）
     */
    @Transactional // 开启事务
    @Modifying // 标记为写操作（删除/更新）
    @Query("DELETE FROM MarketOverviewEntity m WHERE m.author = :author AND m.createdAt BETWEEN :startOfDay AND :endOfDay") // JPQL 删除语句
    int deleteByAuthorAndCreatedAtBetween(@Param("author") String author, // 作者参数
                                          @Param("startOfDay") LocalDateTime startOfDay, // 起始时间
                                          @Param("endOfDay") LocalDateTime endOfDay); // 结束时间

    /**
     * 🆕 一次性查询「每个作者的最新一条大行情分析」
     * 说明：
     *   - 使用原生 SQL，依赖 MySQL 支持
     *   - 利用子查询 + JOIN 拿到每个作者 created_at 最大的那一条
     */
    @Query("""
           SELECT m                                             
           FROM MarketOverviewEntity m                           
           WHERE m.createdAt = (                                  
               SELECT MAX(m2.createdAt)                          
               FROM MarketOverviewEntity m2                       
               WHERE m2.author = m.author                         
           )
           """)
    List<MarketOverviewEntity> findLatestRecordOfEachAuthor(); // 返回所有作者各自最新的一条记录
}
