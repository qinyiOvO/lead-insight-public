package com.leadinsight.module.extract.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抽取结果留痕（表 lead_extract_result，与 lead 一对一，uk_extract_lead）。
 *
 * <p>与 lead 的"生效字段"分离：lead 供查询排序，本表留痕模型原始输出与重试信息，
 * 供论文版本对比与转人工审计。字段与 schema-v1.1.sql 一一对应。</p>
 */
@Data
@TableName("lead_extract_result")
public class LeadExtractResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long leadId;

    /** 模型原始返回（未加工全文）；模型未被调用（无 key/熔断）时为 null */
    private String rawResponse;

    /** 解析并校验通过后的结构化 JSON */
    private String parsedJson;

    /** SUCCESS=成功, FAILED=重试后仍失败转人工, DEGRADED=模型不可用未调用 */
    private String parseStatus;

    /** 重试次数，取值域 0..2（总尝试上限 3 次 = 首次 1 + 重试 2） */
    private Integer retryCount;

    /** 失败原因摘要（JSON 截断/字段缺失/枚举非法等） */
    private String errorMessage;

    private String parserVersion;

    private String promptVersion;

    private String modelName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
