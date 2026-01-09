package com.trading.model; // 声明包名（保持你的工程结构）

import lombok.AllArgsConstructor;   // 引入Lombok注解：全参构造
import lombok.Data;                 // 引入Lombok注解：自动生成getter/setter/toString
import lombok.NoArgsConstructor;    // 引入Lombok注解：无参构造

import java.time.LocalDateTime;     // 导入时间对象

/**
 * 交易决策模型 - 扩展支持AI决定的杠杆leverage
 */
@Data                                   // Lombok自动生成getter/setter等
@NoArgsConstructor                      // 无参构造器
@AllArgsConstructor                     // 全参构造器
public class TradingDecision {

    // === 核心决策字段 ===
    private String action;              // 决策动作：BUY|SELL|HOLD|CLOSE_LONG|CLOSE_SHORT
    private Double confidence;          // 决策置信度：0.0 ~ 1.0
    private String reasoning;           // 决策原因/解释
    private Double positionSize;        // 建议仓位（占总资产比例 0.0~1.0）
    private LocalDateTime decisionTime; // 决策时间戳

    // === 新增字段：AI建议的杠杆 ===
    private Integer leverage;           // 杠杆（1~20，由AI返回并由引擎限幅）
    private Double orderQty;           // 固定数量（base，比如 0.005 BTC）

    // 可选：引用策略记录ID（你的项目里有用到）
    private Long strategyRecordId;      // 策略记录ID（用于落库追踪）

    /**
     * 是否应该执行（非HOLD即执行）
     */
    public boolean shouldExecute() {                // 返回是否需要执行真实下单
        return action != null && !"HOLD".equals(action); // 只要不是HOLD就执行
    }

    /**
     * 便捷构造器（兼容旧代码）
     */
    public TradingDecision(String action, Double confidence, String reasoning,
                           Double positionSize, LocalDateTime decisionTime) { // 旧构造兼容
        this.action = action;                      // 设置动作
        this.confidence = confidence;              // 设置置信度
        this.reasoning = reasoning;                // 设置原因
        this.positionSize = positionSize;          // 设置仓位
        this.decisionTime = decisionTime;          // 设置时间
        this.leverage = 1;                         // 默认杠杆1（若AI未提供）
    }
}
