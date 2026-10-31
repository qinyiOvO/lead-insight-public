package com.leadinsight.module.lead.vo;

import lombok.Data;

/**
 * 评分明细出参（openapi ScoreDetailVO）。
 */
@Data
public class ScoreDetailVO {

    private String ruleCode;
    private String ruleName;
    private String rawValue;
    private Integer weight;
    private Integer itemScore;
    private Integer maxScore;
    private String reason;
}
