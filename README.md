# Trading AI Project

## 项目概述

该项目是一个量化交易系统，旨在通过深度学习和策略回测为用户提供实时交易信号和市场分析。通过集成多种交易所的数据，用户可以实时监控市场动态并根据策略执行交易。此系统支持对接 Bybit 和其他主要加密货币交易平台，具备自动化交易功能。

---

## 主要功能

- **实时市场数据同步**：定时同步各类市场K线数据（15分钟，1小时，日线等），以供分析与决策。
- **策略回测**：根据历史数据回测策略的效果，确保用户可以优化交易策略。
- **交易信号生成**：通过技术指标和市场情绪分析生成交易信号，包括买入、卖出和止损信号。
- **自动交易**：支持通过API与交易所进行自动化交易操作。
- **钱包数据查询与监控**：通过API获取用户钱包数据，包括余额、资产等信息。
- **通知系统**：通过钉钉、短信等渠道推送重要交易信号与账户变动。

---

## 技术栈

- **后端框架**：Spring Boot, Spring Security, Spring Data JPA
- **数据库**：MySQL
- **前端框架**：React (待补充)
- **交易所API**：Bybit, Binance（支持更多平台的扩展）
- **监控与日志**：SLF4J, Logback
- **任务调度**：Spring Scheduler
- **依赖管理**：Maven

---

## 环境要求

- JDK 8 或以上
- MySQL 5.7 或以上
- 适配的加密货币交易平台API密钥（如 Bybit 或 Binance）
- 钉钉Webhook配置（可选）

---

## 项目结构

```text
├── src
│   ├── main
│   │   ├── java
│   │   │   ├── com.trading
│   │   │   │   ├── job                 # 定时任务与调度器
│   │   │   │   ├── repository          # 数据库访问层
│   │   │   │   ├── service             # 核心服务逻辑
│   │   │   │   ├── controller          # 控制器，处理HTTP请求
│   │   │   │   ├── entity              # 实体类（如：MarketKlineEntity）
│   │   │   │   └── util                # 工具类（如：API请求工具类）
│   │   ├── resources
│   │   │   ├── application.yml         # 配置文件
│   │   │   ├── logback-spring.xml      # 日志配置文件
│   │   │   └── tasks                   # 定时任务配置文件
│   └── test
│       └── java
└── pom.xml                             # Maven依赖管理
