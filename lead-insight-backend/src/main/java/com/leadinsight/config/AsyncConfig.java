package com.leadinsight.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步执行配置（J-1 裁定 + T3-01 批量导入）。
 *
 * <p>两个独立线程池，互不挤占：llmExtractExecutor 服务单条粘贴的异步抽取（有界队列 50）；
 * importTaskExecutor 服务批量导入任务（亮点2）——整批任务串行占用 1 线程、批内逐条同步抽取，
 * 若与单条抽取共用池，大批量任务会饿死单条异步抽取，故按资源隔离原则拆分。
 * 两者均 CallerRuns 拒绝策略——队列满时由提交线程亲自执行，任务绝不丢弃、请求不失败。</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("llmExtractExecutor")
    public ThreadPoolTaskExecutor llmExtractExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("llm-extract-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机：等待在途抽取落库后再退出，避免终态丢失
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean("importTaskExecutor")
    public ThreadPoolTaskExecutor importTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 毕设体量：并发批量任务数有限，2-4 线程 + 20 队列足够；CallerRuns 兜底（ADR-006 口径）
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("import-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
