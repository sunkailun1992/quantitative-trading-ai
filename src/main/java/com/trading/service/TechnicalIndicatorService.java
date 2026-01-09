package com.trading.service;                                           // 包声明：服务所在包

// ===================== 依赖导入 =====================

import lombok.extern.slf4j.Slf4j;                                      // 引入Lombok日志
import org.springframework.stereotype.Service;                         // 引入Spring服务注解

import java.util.ArrayList;                                            // 引入ArrayList
import java.util.Collections;                                          // 引入Collections工具类
import java.util.List;                                                 // 引入List接口
import java.util.Objects;                                              // 引入Objects工具
import java.util.stream.Collectors;                                    // 引入流式收集器

/**
 * 技术指标服务（TradingView对齐版）                                   // 类注释：所有指标尽量与TradingView一致
 * - EMA 使用首 period 根 SMA 作为种子，之后EMA递推                     // 说明：EMA种子=前period均值
 * - MACD 使用 EMA(12/26) 与 DIF 的 EMA(9)，三者种子均用SMA            // 说明：MACD三条曲线都以SMA种子
 * - RSI 使用 Wilder’s 平滑方法（初始化SMA，后续EMA式平滑）            // 说明：RSI算法采用Wilder平滑
 * - ATR 使用 TR 平滑（初始化SMA，后续Wilder平滑）                      // 说明：ATR初始化为TR均值
 * - BOLL 使用 population stdev（与TV默认一致）                         // 说明：布林带标准差为总体标准差
 */
@Slf4j                                                                 // 开启日志
@Service                                                               // 标记为Spring服务
public class TechnicalIndicatorService {                               // 技术指标服务类开始

    // ===================== 内部价格/成交量缓存（可选） =====================
    private final List<Double> priceHistory = new ArrayList<>();       // 价格历史（用于简易统计/后备）
    private final List<Double> volumeHistory = new ArrayList<>();      // 成交量历史（用于简易统计）

    // ===================== 公共辅助：历史数据推入 =====================
    public void addPrice(double price) {                               // 方法：追加一个价格点
        if (price <= 0 || Double.isNaN(price) || Double.isInfinite(price)) { // 若价格非法
            log.warn("⚠️ 无效价格: {}，跳过添加", price);                    // 打印警告
            return;                                                    // 返回
        }
        priceHistory.add(price);                                       // 添加价格
        if (priceHistory.size() > 5000) {                              // 若超过上限（避免内存飙升）
            priceHistory.remove(0);                                    // 丢弃最旧元素
        }
    }

    public double getCurrentPrice() {                                  // 方法：获取最新价格
        return priceHistory.isEmpty() ? 0.0 : last(priceHistory);      // 若为空返回0，否则取末尾
    }

    public double getCurrentVolume() {                                 // 方法：获取最新成交量
        return volumeHistory.isEmpty() ? 0.0 : last(volumeHistory);    // 若为空返回0，否则取末尾
    }

    public List<Double> getRecentPrices(int n) {                       // 方法：获取最近n个价格
        if (n <= 0 || priceHistory.isEmpty()) return new ArrayList<>();// 边界：n无效或无数据
        int from = Math.max(0, priceHistory.size() - n);               // 计算起始下标
        return new ArrayList<>(priceHistory.subList(from, priceHistory.size())); // 返回副本
    }

    // ===================== EMA（与TradingView一致） =====================

    /**
     * 计算单个周期的最新EMA值（与TradingView一致）                      // 注释：返回序列最后一根的EMA
     *
     * @param closes 升序收盘价序列                                     // 参数：必须最老→最新
     * @param period 周期（如20/50/144/288/338）                         // 参数：EMA周期
     */
    public double calculateEMA(List<Double> closes, int period) {      // 方法：计算EMA最新值
        List<Double> src = sanitize(closes);                           // 清洗空值/NaN并复制
        if (src.size() < period || period < 1) {                       // 若样本不足或周期非法
            log.debug("⚠️ EMA计算: 数据不足 (size={}, period={})", src.size(), period); // 打印警告
            return 0.0;                                                // 返回0兜底
        }
        double seed = mean(src.subList(0, period));                    // 取前period根SMA作为种子
        double k = 2.0 / (period + 1.0);                               // 计算平滑系数
        double ema = seed;                                             // 初始化EMA
        for (int i = period; i < src.size(); i++) {                    // 从第period根开始迭代
            ema = ema + k * (src.get(i) - ema);                        // EMA递推：ema += k*(p-ema)
        }
        return ema;                                                    // 返回最新EMA
    }

    /**
     * 计算完整EMA序列（便于校验/画图）                                  // 注释：从第period根开始有值，其前为null
     */
    public List<Double> calculateEMASeries(List<Double> closes, int period) { // 方法：计算整条EMA曲线
        List<Double> src = sanitize(closes);                           // 清洗数据
        List<Double> out = new ArrayList<>(Collections.nCopies(src.size(), null)); // 预置null输出
        if (src.size() < period || period < 1) return out;             // 样本不足直接返回
        double seed = mean(src.subList(0, period));                    // 种子：SMA(period)
        double k = 2.0 / (period + 1.0);                               // 平滑系数
        double ema = seed;                                             // 初始化种子
        out.set(period - 1, ema);                                      // 第period-1索引处写入首个EMA
        for (int i = period; i < src.size(); i++) {                    // 遍历剩余
            ema = ema + k * (src.get(i) - ema);                        // EMA递推
            out.set(i, ema);                                           // 写入当前位置EMA
        }
        return out;                                                    // 返回序列
    }

    // ===================== MACD（12,26,9 与TradingView一致） =====================

    /**
     * 计算MACD最新值（DIF/DEA/HIST）                                   // 注释：对齐TV的三条曲线
     * - EMA12/EMA26 种子：各自前N根SMA                                 // 规则：SMA作为EMA种子
     * - DIF = EMA12 - EMA26                                           // 定义：快慢差
     * - DEA = EMA(DIF, 9)（种子：前9根DIF的SMA）                        // 规则：DEA种子=前9根DIF均值
     * - HIST = (DIF - DEA) * 2                                        // 定义：柱状图×2
     */
    public MACDResult calculateMACD(List<Double> closes, int fast, int slow, int signal) { // 方法：计算MACD
        List<Double> src = sanitize(closes);                           // 清洗数据
        if (src.size() < Math.max(slow, fast) + signal) {              // 长度检查：至少要覆盖慢线+信号
            log.debug("⚠️ MACD计算: 数据不足 (size={}, need>={})", src.size(), Math.max(slow, fast) + signal); // 打印警告
            return new MACDResult(0, 0, 0);                            // 返回零结果
        }
        List<Double> emaFast = calculateEMASeries(src, fast);          // 计算EMA_fast序列
        List<Double> emaSlow = calculateEMASeries(src, slow);          // 计算EMA_slow序列
        List<Double> difSeries = new ArrayList<>(src.size());          // 创建DIF序列容器
        for (int i = 0; i < src.size(); i++) {                         // 遍历所有位置
            Double f = emaFast.get(i);                                 // 取EMA_fast[i]
            Double s = emaSlow.get(i);                                 // 取EMA_slow[i]
            difSeries.add(f == null || s == null ? null : (f - s));    // 若任一为空则写null，否则写差值
        }
        List<Double> deaSeries = emaOnNullable(difSeries, signal);     // 对DIF做EMA信号平滑
        double dif = lastNonNull(difSeries);                           // 取最新非空DIF
        double dea = lastNonNull(deaSeries);                           // 取最新非空DEA
        double hist = (dif - dea) * 2.0;                               // 计算HIST（×2）
        return new MACDResult(dif, dea, hist);                         // 返回结果对象
    }

    // ===================== RSI（Wilder’s smoothing） =====================

    /**
     * 计算RSI最新值（Wilder算法）                                      // 注释：先SMA，后EMA式平滑
     */
    public Double calculateRSI(List<Double> closes, int period) {      // 方法：计算RSI
        List<Double> src = sanitize(closes);                           // 清洗数据
        if (src.size() <= period) {                                    // 样本不足检查
            log.debug("⚠️ RSI计算: 数据不足 (size={}, period={})", src.size(), period); // 打印警告
            return 50.0;                                               // 返回中性值
        }
        double gain = 0.0, loss = 0.0;                                 // 初始化累计涨跌
        for (int i = 1; i <= period; i++) {                            // 统计前period段
            double ch = src.get(i) - src.get(i - 1);                   // 当前差分
            if (ch > 0) gain += ch;
            else loss -= ch;                   // 累计涨跌
        }
        double avgGain = gain / period;                                // 初始平均涨幅（SMA）
        double avgLoss = loss / period;                                // 初始平均跌幅（SMA）
        for (int i = period + 1; i < src.size(); i++) {                // 后续用Wilder平滑
            double ch = src.get(i) - src.get(i - 1);                   // 差分
            double up = Math.max(ch, 0);                               // 上涨部分
            double dn = Math.max(-ch, 0);                              // 下跌部分
            avgGain = (avgGain * (period - 1) + up) / period;          // 平滑更新平均涨幅
            avgLoss = (avgLoss * (period - 1) + dn) / period;          // 平滑更新平均跌幅
        }
        if (avgLoss == 0.0) return 100.0;                              // 若无下跌则RSI=100
        double rs = avgGain / avgLoss;                                 // 计算RS
        double rsi = 100.0 - (100.0 / (1.0 + rs));                     // 计算RSI
        return Math.max(0.0, Math.min(100.0, rsi));                    // 裁剪范围0..100
    }

    // ===================== ATR（Wilder’s smoothing） =====================

    /**
     * 计算ATR最新值（可选择是否排除末尾未收盘K线）                       // 注释：excludeLast=true可对齐TV“仅收盘”模式
     */
    public double calculateATR(List<Double> highs, List<Double> lows, List<Double> closes, int period, boolean excludeLast) { // 方法：计算ATR（带排除选项）
        List<Double> H = sanitize(highs);                              // 清洗高点
        List<Double> L = sanitize(lows);                               // 清洗低点
        List<Double> C = sanitize(closes);                             // 清洗收盘
        int size = Math.min(H.size(), Math.min(L.size(), C.size()));   // 对齐长度
        if (excludeLast && size > 0) {                                 // 若要求排除最后一根
            size -= 1;                                                 // 末尾减一
        }
        if (size <= period) {                                          // 样本不足检查
            log.debug("⚠️ ATR计算: 数据不足 (size={}, period={})", size, period); // 打印警告
            return 0.0;                                                // 返回0
        }
        double[] trs = new double[size];                               // 创建TR数组
        trs[0] = H.get(0) - L.get(0);                                  // 第一根TR定义为高低差
        for (int i = 1; i < size; i++) {                               // 遍历其余各根
            double tr1 = H.get(i) - L.get(i);                          // 当日高低差
            double tr2 = Math.abs(H.get(i) - C.get(i - 1));            // 高-昨收
            double tr3 = Math.abs(L.get(i) - C.get(i - 1));            // 低-昨收
            trs[i] = Math.max(tr1, Math.max(tr2, tr3));                // TR取三者最大
        }
        double atr = 0.0;                                              // 准备初始ATR
        for (int i = 1; i <= period; i++) atr += trs[i];               // 前period根TR求和（从1开始）
        atr /= period;                                                 // 初始ATR为均值
        for (int i = period + 1; i < size; i++) {                      // 之后用Wilder平滑
            atr = ((atr * (period - 1)) + trs[i]) / period;            // ATR平滑递推
        }
        return atr;                                                    // 返回最新ATR
    }

    /**
     * 默认对齐：包含已收盘最新K线（不排除）                              // 注释：与多数交易所实时图一致
     */
    public double calculateATR(List<Double> highs, List<Double> lows, List<Double> closes, int period) { // 方法：ATR重载（默认）
        return calculateATR(highs, lows, closes, period, false);       // 调用带开关的方法
    }

    // ===================== 布林带（位置/带宽） =====================

    /**
     * 计算布林带位置（0~100，50为中轨）                                 // 注释：position= (price-lower)/(upper-lower)*100
     */
    public Double calculateBollingerBandsPosition(List<Double> closes, int period) { // 方法：BOLL位置
        List<Double> src = sanitize(closes);                           // 清洗数据
        if (src.size() < period) {                                     // 样本不足
            log.debug("⚠️ 布林带位置: 数据不足 (size={}, period={})", src.size(), period); // 打印警告
            return 50.0;                                               // 返回中性50
        }
        List<Double> win = src.subList(src.size() - period, src.size());// 取窗口
        double ma = mean(win);                                         // 均值=中轨
        double sd = stdPopulation(win, ma);                            // 总体标准差
        double upper = ma + 2 * sd;                                    // 上轨=MA+2σ
        double lower = ma - 2 * sd;                                    // 下轨=MA-2σ
        double last = last(src);                                       // 最新收盘
        if (upper == lower) return 50.0;                               // 避免除零
        double pos = (last - lower) / (upper - lower) * 100.0;         // 计算百分比位置
        return clamp(pos, 0.0, 100.0);                                 // 裁剪到0..100
    }

    /**
     * 计算布林带带宽（百分比：4σ / MA）                                  // 注释：等价于(上-下)/MA*100
     */
    public Double calculateBBBandwidth(List<Double> closes, int period) { // 方法：BOLL带宽
        List<Double> src = sanitize(closes);                           // 清洗数据
        if (src.size() < period) {                                     // 样本不足
            log.debug("⚠️ 布林带带宽: 数据不足 (size={}, period={})", src.size(), period); // 打印警告
            return 10.0;                                               // 返回兜底
        }
        List<Double> win = src.subList(src.size() - period, src.size());// 取窗口
        double ma = mean(win);                                         // 计算均值
        if (ma == 0.0) return 0.0;                                     // 避免除零
        double sd = stdPopulation(win, ma);                            // 计算总体标准差
        double bandwidth = (4.0 * sd / ma) * 100.0;                    // 带宽百分比=4σ/MA*100
        return clamp(bandwidth, 0.0, 10000.0);                         // 合理裁剪
    }

    // ===================== 工具/辅助函数 =====================
    private static <T> T last(List<T> list) {                          // 工具：取列表最后一个
        return list.get(list.size() - 1);                              // 返回末尾元素
    }

    private static double lastNonNull(List<Double> list) {             // 工具：取最后一个非空double
        for (int i = list.size() - 1; i >= 0; i--) {                   // 从后往前找
            Double v = list.get(i);                                    // 取元素
            if (v != null) return v;                                   // 非空直接返回
        }
        return 0.0;                                                    // 否则返回0
    }

    private static List<Double> sanitize(List<Double> src) {           // 工具：清洗数据
        if (src == null || src.isEmpty()) return new ArrayList<>();    // 空则返回空列表
        return src.stream()                                            // 流式处理
                .filter(Objects::nonNull)                              // 过滤null
                .filter(d -> !Double.isNaN(d) && !Double.isInfinite(d))// 过滤NaN/Inf
                .collect(Collectors.toList());                         // 收集为新列表
    }

    private static double mean(List<Double> list) {                    // 工具：均值
        if (list.isEmpty()) return 0.0;                                // 空返回0
        double sum = 0.0;                                              // 累加器
        for (double d : list) sum += d;                                // 累加
        return sum / list.size();                                      // 返回均值
    }

    private static double stdPopulation(List<Double> list, double mean) { // 工具：总体标准差
        if (list.isEmpty()) return 0.0;                                // 空返回0
        double acc = 0.0;                                              // 方差累计
        for (double d : list) acc += (d - mean) * (d - mean);          // 累计平方差
        return Math.sqrt(acc / list.size());                           // 返回σ=√(Σ/n)
    }

    private static double clamp(double v, double lo, double hi) {      // 工具：裁剪范围
        return Math.max(lo, Math.min(hi, v));                          // 返回限制后的值
    }

    /**
     * 对可空序列做EMA（用于MACD的DEA）                                  // 工具：对 Nullable DIF 做EMA
     * - 先找到第一段连续非空的前 signal 根，取SMA为种子                  // 逻辑：对齐TV的dea初始化
     * - 之后按EMA递推                                                     // 逻辑：标准EMA
     */
    private static List<Double> emaOnNullable(List<Double> series, int signal) { // 方法：对可空序列做EMA
        List<Double> out = new ArrayList<>(Collections.nCopies(series.size(), null)); // 准备输出
        int i = 0;                                                      // 遍历索引
        while (i < series.size() && series.get(i) == null) i++;         // 跳过前导null
        if (i >= series.size()) return out;                              // 全为空则返回
        int start = i;                                                   // 非空起点
        // 若后续非空数据不足以形成seed，则尽可能推断                         // 说明：鲁棒性处理
        int endSeed = Math.min(series.size(), start + signal);           // 种子结束索引（不含）
        List<Double> seedSlice = new ArrayList<>();                      // 种子切片容器
        for (int k = start; k < endSeed; k++) {                          // 收集至signal个
            if (series.get(k) == null) break;                            // 遇null终止
            seedSlice.add(series.get(k));                                // 添加值
        }
        if (seedSlice.isEmpty()) return out;                              // 若仍为空直接返回
        double ema = mean(seedSlice);                                    // 种子=SMA(signal)
        double kf = 2.0 / (signal + 1.0);                                // 平滑系数
        out.set(start + seedSlice.size() - 1, ema);                      // 在种子末尾写入首个EMA
        for (int t = start + seedSlice.size(); t < series.size(); t++) { // 从下一位开始迭代
            Double v = series.get(t);                                    // 当前值
            if (v == null) {
                out.set(t, null);
                continue;
            }               // 遇null则写null跳过
            ema = ema + kf * (v - ema);                                  // EMA递推
            out.set(t, ema);                                             // 写入结果
        }
        return out;                                                      // 返回DEA序列
    }

    // ===================== 结果类型：MACD =====================
    public static class MACDResult {                                    // 内部类：MACD结果
        private final double dif;                                       // 字段：DIF
        private final double dea;                                       // 字段：DEA
        private final double hist;                                      // 字段：HIST

        public MACDResult(double dif, double dea, double hist) {        // 构造器
            this.dif = dif;                                             // 赋值DIF
            this.dea = dea;                                             // 赋值DEA
            this.hist = hist;                                           // 赋值HIST
        }

        public double getDif() {
            return dif;
        }                          // Getter：DIF

        public double getDea() {
            return dea;
        }                          // Getter：DEA

        public double getHistogram() {
            return hist;
        }                   // Getter：HIST

        @Override
        public String toString() {                             // 重写toString
            return String.format("MACD[DIF=%.4f, DEA=%.4f, HIST=%.4f]", dif, dea, hist); // 友好输出
        }
    }
}                                                                       // 类结束
