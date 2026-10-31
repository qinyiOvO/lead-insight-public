package com.leadinsight.module.extract.parser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 模型抽取结果目标对象（亮点1 ExtractSchema）。
 *
 * <p>字段名与外置模板 json 示例严格一致；反序列化宽松（未知字段忽略、null 保留），
 * 业务级校验在 {@link ExtractResultParser} 完成（枚举合法性 / 数字与日期可解析）。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtractedLead {

    @JsonProperty("customerName")
    private String customerName;

    @JsonProperty("companyName")
    private String companyName;

    @JsonProperty("contactInfo")
    private String contactInfo;

    /** 必须合法命中五类枚举之一，否则校验失败 */
    @JsonProperty("intentCategory")
    private String intentCategory;

    @JsonProperty("demandSummary")
    private String demandSummary;

    @JsonProperty("budgetAmount")
    private BigDecimal budgetAmount;

    @JsonProperty("budgetText")
    private String budgetText;

    /** 模型必须输出 yyyy-MM-dd，格式非法即校验失败 */
    @JsonProperty("expectedTime")
    private LocalDate expectedTime;

    @JsonProperty("expectedTimeText")
    private String expectedTimeText;

    @JsonProperty("companyScale")
    private String companyScale;

    @JsonProperty("keywordsHit")
    private String keywordsHit;
}
