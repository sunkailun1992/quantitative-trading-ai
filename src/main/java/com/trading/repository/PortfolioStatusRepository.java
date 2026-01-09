package com.trading.repository;

import com.trading.entity.PortfolioStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 投资组合数据库仓储接口
 * 提供对 portfolio_status 表的 CRUD 操作
 */
@Repository
public interface PortfolioStatusRepository extends JpaRepository<PortfolioStatusEntity, Long> {

    /** 查询最近的50条资产记录 */
    List<PortfolioStatusEntity> findTop50ByOrderByCreatedAtDesc();

    /** 根据交易对查询最近记录 */
    List<PortfolioStatusEntity> findTop10BySymbolOrderByCreatedAtDesc(String symbol);
}
