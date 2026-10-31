package com.leadinsight.module.extract.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型调用日志（表 model_call_log，亮点4 可观测性）。
 *
 * <p>每次调用（含每次重试）落一行：耗时、状态码、结果分类、token、降级标记，
 * 为论文「限流与降级有效性」提供量化数据。<b>不含任何 key 明文</b>，
 * 请求侧只落 SHA-256 指纹。字段与 schema-v1.1.sql 一一对应。</p>
 */
@Data
@TableName("model_call_log")
public class ModelCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联线索 ID（单条抽取场景） */
    private Long leadId;

    /** 关联导入任务 ID（R3 批量场景） */
    private Long importTaskId;

    /** EXTRACT=线索抽取, CLASSIFY=意图判定 */
    private String scene;

    private String modelName;

    /** 请求内容 SHA-256 指纹（便于统计重复请求/缓存命中），不落原文不落 key */
    private String requestFingerprint;

    private Integer httpStatus;

    /** SUCCESS / RATE_LIMITED / TIMEOUT / ERROR / PARSE_FAIL */
    private String callResult;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Integer latencyMs;

    /** 第几次尝试（0=首次） */
    private Integer retryNo;

    /** 1=本次触发降级 */
    private Integer degraded;

    private String errorCode;

    private String errorMessage;

    private LocalDateTime createdAt;
}
