package com.leadinsight.module.lead.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.lead.enums.ExtractStatus;
import com.leadinsight.module.lead.enums.IntentCategory;
import com.leadinsight.module.lead.enums.LeadStatus;
import com.leadinsight.module.lead.enums.PriorityLevel;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 线索出参（openapi LeadVO，v1.2.1）。字段名与 openapi 严格一致；LeadDetailVO 继承本类。
 *
 * <p>v1.2.1 新增两字段：{@code expectedTime}（规范化的期望日期，openapi format=date）与
 * {@code ownerName}（负责人姓名，取 sys_user.real_name）。{@link #from(Lead)} 只铺实体可直取的
 * 字段；{@code ownerName} 依赖 sys_user，不在实体上，由 Service 层批量回填（未认领为 null）。</p>
 */
@Data
public class LeadVO {

    private Long id;
    private String subjectTitle;
    private String customerName;
    private String companyName;
    private IntentCategory intentCategory;
    private String demandSummary;

    /**
     * 期望时间。openapi 声明 format=date，前端按日期渲染，
     * 故显式钉死为 yyyy-MM-dd 字符串序列化，规避 LocalDate 被写成数组 [2026,10,27] 的风险。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate expectedTime;

    private Integer priorityScore;
    private PriorityLevel priorityLevel;
    private String scoreReason;
    private LeadStatus status;
    private ExtractStatus extractStatus;
    /** 抽取整体置信度（系统自算）；R1 手工录入恒为 null */
    private BigDecimal extractConfidence;
    /** 抽取置信度档位；null=未抽取/降级/未评估 */
    private String confidenceLevel;
    private Long ownerId;
    /** 负责人姓名，取 sys_user.real_name；未认领为 null（禁止空串） */
    private String ownerName;
    /** SINGLE / BATCH */
    private String importSource;
    private LocalDateTime createdAt;

    public static LeadVO from(Lead lead) {
        LeadVO vo = new LeadVO();
        vo.setId(lead.getId());
        vo.setSubjectTitle(lead.getSubjectTitle());
        vo.setCustomerName(lead.getCustomerName());
        vo.setCompanyName(lead.getCompanyName());
        vo.setIntentCategory(IntentCategory.fromCode(lead.getIntentCategory()));
        vo.setDemandSummary(lead.getDemandSummary());
        vo.setExpectedTime(lead.getExpectedTime());
        vo.setPriorityScore(lead.getPriorityScore());
        vo.setPriorityLevel(PriorityLevel.fromCode(lead.getPriorityLevel()));
        vo.setScoreReason(lead.getScoreReason());
        vo.setStatus(LeadStatus.fromCode(lead.getStatus()));
        vo.setExtractStatus(ExtractStatus.fromCode(lead.getExtractStatus()));
        vo.setExtractConfidence(lead.getExtractConfidence());
        vo.setConfidenceLevel(lead.getConfidenceLevel());
        vo.setOwnerId(lead.getOwnerId());
        // ownerName 来自 sys_user，不在此处取；由 Service 层批量回填（未认领保持 null）
        vo.setImportSource(lead.getImportSource());
        vo.setCreatedAt(lead.getCreatedAt());
        return vo;
    }
}
