package com.leadinsight.module.lead.dto;

import com.leadinsight.module.lead.enums.CompanyScale;
import com.leadinsight.module.lead.enums.IntentCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 创建线索入参（openapi 1.2.0 双模式）。
 *
 * <p>模式判定：下列手工字段「任一非空」即处于手工录入模式（M0/R1 启用），
 * 后端不调用模型、字段直接生效并立即评分；否则为粘贴模式（M1/R2 启用）。
 * 两种模式下 originalText 均必填——内容指纹去重依赖该字段。</p>
 *
 * <p>手工模式下 customerName 与 demandSummary 不得为空（AC-08），
 * 该校验依赖跨字段判定，故在 Service 层执行并返回字段级错误。</p>
 */
@Data
public class LeadCreateRequest {

    @Size(max = 255, message = "邮件主题长度不能超过 255 个字符")
    private String subjectTitle;

    @NotBlank(message = "邮件正文不能为空")
    private String originalText;

    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;

    // ---------------------- 手工录入字段（任一非空即手工模式） ----------------------

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

    /** 是否处于手工录入模式：任一手工字段非空即为真 */
    public boolean isManualMode() {
        return hasText(customerName)
                || hasText(companyName)
                || hasText(contactInfo)
                || intentCategory != null
                || hasText(demandSummary)
                || budgetAmount != null
                || expectedTime != null
                || companyScale != null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
