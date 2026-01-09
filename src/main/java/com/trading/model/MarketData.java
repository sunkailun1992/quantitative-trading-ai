package com.trading.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 📊 MarketData 市场数据模型（增强版）
 * 支持多周期、多维指标（RSI / MACD / BOLL / EMA / ATR）
 * 用于AI交易引擎的核心数据输入。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder // ✅ 启用 Builder 模式
public class MarketData {

    /** =========================
     * 🧩 基础市场信息
     * ========================= */
    private String symbol;              // 交易对（如 BTCUSDT）
    private String period;              // ✅ 周期标识（15m / 1h / 1d / 1w）
    private Double currentPrice;        // 当前价格
    private Double priceChange24h;      // 24小时价格变化百分比 (%)
    private Double volume;              // 成交量
    private LocalDateTime timestamp;    // 数据时间（通常为最新一根K线时间）

    /** =========================
     * 📈 技术指标区
     * ========================= */
    private Double rsi;                 // RSI 相对强弱指数
    private Double macdDif;             // MACD DIF 快线
    private Double macdDea;             // MACD DEA 慢线
    private Double macdHistogram;       // MACD 柱状图（DIF - DEA）
    private Double bbPosition;          // 布林带位置（0~1）
    private Double bbBandwidth;         // 布林带带宽
    private Double ema20;               // EMA(20) 短期趋势
    private Double ema50;               // EMA(50) 中期趋势
    private Double ema144;              // EMA(144) 长周期趋势
    private Double ema168;              // EMA(168) 扩展趋势
    private Double ema288;              // EMA(288) 更长趋势
    private Double ema338;              // EMA(338) 超长趋势
    private Double atr3;                // ATR(3) 短期波动率
    private Double atr14;               // ATR(14) 标准波动率

    /** =========================
     * 🧠 未来可扩展指标
     * ========================= */
    private Double trendStrength;       // 趋势强度评分（AI可生成）
    private Double momentumScore;       // 动量评分
    private Double volatilityScore;     // 波动率评分
}
