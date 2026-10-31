package com.leadinsight.module.lead.dto;

import com.leadinsight.module.lead.enums.CompanyScale;
import com.leadinsight.module.lead.enums.IntentCategory;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 编辑线索入参（openapi LeadUpdateRequest）。所有字段可选，仅非空字段生效。
 */
@Data
public class LeadUpdateRequest {

    @Size(max = 100, message = "客户名称长度不能超过 100 个字符")
    private String customerName;

    @Size(max = 150, message = "公司名称长度不能超过 150 个字符")
    private String companyName;

    @Size(max = 150, message = "联系方式长度不能超过 150 个字符")
    private String contactInfo;

    private IntentCategory intentCategory;

    @Size(max = 500, message = "需求要点长度不能超过 500 个字符")
    private String demandSummary;

    private BigDecimal budgetAmount;

    private LocalDate expectedTime;

    private CompanyScale companyScale;

    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;
}
