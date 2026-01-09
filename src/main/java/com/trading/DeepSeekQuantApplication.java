package com.trading;

// 导入必要的包

import com.trading.job.RealMarketDataService; // 真实市场数据服务
import lombok.RequiredArgsConstructor; // Lombok：自动生成构造函数
import lombok.extern.slf4j.Slf4j; // Lombok：日志注解
import org.springframework.boot.CommandLineRunner; // Spring Boot命令行运行器
import org.springframework.boot.SpringApplication; // Spring Boot应用启动
import org.springframework.boot.autoconfigure.SpringBootApplication; // Spring Boot应用注解
import org.springframework.scheduling.annotation.EnableAsync; // 启用异步支持
import org.springframework.scheduling.annotation.EnableScheduling; // 启用定时任务支持

/**
 * DeepSeek量化交易应用主类 - 完整实现
 * 负责应用启动、初始化和主要流程控制
 */
@Slf4j // Lombok：自动生成日志对象
@SpringBootApplication // Spring Boot：声明为主应用类
@EnableAsync // Spring：启用异步处理
@EnableScheduling // Spring：启用定时任务
@RequiredArgsConstructor // Lombok：自动生成构造函数
public class DeepSeekQuantApplication implements CommandLineRunner { // 实现CommandLineRunner接口

    // 依赖注入
    private final RealMarketDataService realMarketDataService; // 真实市场数据服务

    /**
     * 应用主入口方法
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(DeepSeekQuantApplication.class, args); // 启动Spring Boot应用
    }

    /**
     * 应用启动后执行的方法 - 完整实现
     *
     * @param args 命令行参数
     */
    @Override
    public void run(String... args) throws Exception {
        log.info("🚀 DeepSeek AI量化交易系统启动完成"); // 记录信息日志

        // 步骤1: 系统启动横幅
        printStartupBanner(); // 打印启动横幅

        // 步骤5: 自动启动数据流（可选）
        log.info("🔄 启动实时市场数据流..."); // 记录信息日志
        try {
            realMarketDataService.enableDataStream(); // 启用数据流
            log.info("✅ 实时市场数据流已自动启动"); // 记录信息日志
        } catch (Exception e) { // 捕获异常
            log.error("❌ 启动实时市场数据流失败: {}", e.getMessage()); // 记录错误日志
        }

        log.info("🎉 DeepSeek AI量化交易系统启动流程全部完成"); // 记录信息日志
    }

    /**
     * 打印启动横幅
     */
    private void printStartupBanner() {
        log.info("\n" +
                "================================================================================\n" +
                "   _____                    _    ______                  _      __   __          \n" +
                "  |  __ \\                  | |   |  _  \\                | |     \\ \\ / /          \n" +
                "  | |  \\/  ___   __ _  ___ | | __| | | | _____   __  ___| | __   \\ V /___   _ __ \n" +
                "  | | __  / _ \\ / _` |/ __|| |/ /| | | |/ _ \\ \\ / / / __| |/ /    \\ // _ \\ | '__|\n" +
                "  | |_\\ \\|  __/| (_| |\\__ \\|   < | |/ /|  __/\\ V / | (__|   <     | | (_) || |   \n" +
                "   \\____/ \\___| \\__,_||___/|_|\\_\\|___/  \\___| \\_/   \\___|_|\\_\\    \\_/\\___/ |_|   \n" +
                "                                                                                \n" +
                "                          AI量化交易系统 v1.0 - 基于DeepSeek技术                 \n" +
                "================================================================================\n");
    }

    /**
     * 优雅关闭处理
     */
    @Override
    public void finalize() {
        try {
            log.info("🔄 应用关闭中，停止数据流..."); // 记录信息日志
            realMarketDataService.disableDataStream(); // 禁用数据流
            log.info("✅ 数据流已停止，应用关闭完成"); // 记录信息日志
        } catch (Exception e) { // 捕获异常
            log.error("❌ 应用关闭过程中发生错误: {}", e.getMessage()); // 记录错误日志
        }
    }


}