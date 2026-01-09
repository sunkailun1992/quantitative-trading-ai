package com.trading.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> { // 统一API响应模型
    private Boolean success; // 请求是否成功
    private String message; // 响应消息
    private T data; // 响应数据
    private LocalDateTime timestamp; // 响应时间
    
    public static <T> ApiResponse<T> success(T data) { // 成功响应静态工厂方法
        return new ApiResponse<>(true, "Success", data, LocalDateTime.now()); // 返回成功响应
    }
    
    public static <T> ApiResponse<T> error(String message) { // 错误响应静态工厂方法
        return new ApiResponse<>(false, message, null, LocalDateTime.now()); // 返回错误响应
    }
}
