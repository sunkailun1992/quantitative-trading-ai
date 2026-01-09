package com.trading.controller;

import com.trading.model.ApiResponse;
import com.trading.job.RealMarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/market")
@RequiredArgsConstructor
public class MarketDataController { // 市场数据控制器类
    
    private final RealMarketDataService realMarketDataService; // 真实市场数据服务依赖注入
    
    @PostMapping("/stream/start") // 处理POST请求，启动数据流
    public ResponseEntity<ApiResponse<String>> startDataStream() { // 启动数据流接口
        try {
            realMarketDataService.enableDataStream(); // 启用数据流
            return ResponseEntity.ok(ApiResponse.success("实时市场数据流已启动")); // 返回成功响应
        } catch (Exception e) { // 捕获异常
            log.error("启动数据流失败: {}", e.getMessage(), e); // 记录错误日志
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage())); // 返回错误响应
        }
    }
    
    @PostMapping("/stream/stop") // 处理POST请求，停止数据流
    public ResponseEntity<ApiResponse<String>> stopDataStream() { // 停止数据流接口
        try {
            realMarketDataService.disableDataStream(); // 禁用数据流
            return ResponseEntity.ok(ApiResponse.success("实时市场数据流已停止")); // 返回成功响应
        } catch (Exception e) { // 捕获异常
            log.error("停止数据流失败: {}", e.getMessage(), e); // 记录错误日志
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage())); // 返回错误响应
        }
    }
}