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
 * {@code ownerName}（负责人姓名，取 sys_user.real_name）。v1.3.3（DEF-09）新增 {@code updatedAt}
 * （最后修改时间，AC-09 可观测出口，实体直取）。{@link #from(Lead)} 只铺实体可直取的
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

    /**
     * 最后修改时间（DEF-09：AC-09「保存变更并记录最后修改时间」的可观测出口）。
     * 实体 updated_at 为 ON UPDATE CURRENT_TIMESTAMP，PATCH 时服务端显式刷新；
     * 创建时等于 createdAt，编辑后随之变化。LeadDetailVO 继承本字段，不重复声明。
     */
    private LocalDateTime updatedAt;

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
        vo.setUpdatedAt(lead.getUpdatedAt());
        return vo;
    }
}
