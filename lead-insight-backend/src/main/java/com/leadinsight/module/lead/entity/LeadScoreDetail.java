package com.leadinsight.module.lead.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 线索评分明细（表 lead_score_detail，与 lead 一对多，唯一键 (lead_id, rule_code)）。
 */
@Data
@TableName("lead_score_detail")
public class LeadScoreDetail {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long leadId;

    /** 评分项编码：INTENT / BUDGET / URGENCY / COMPANY / KEYWORD */
    private String ruleCode;

    /** 评分项名称（中文） */
    private String ruleName;

    /** 该项原始取值 */
    private String rawValue;

    private Integer weight;

    private Integer itemScore;

    private Integer maxScore;

    private String reason;

    private LocalDateTime createdAt;
}
