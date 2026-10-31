package com.leadinsight.module.lead.dto;

import com.leadinsight.module.lead.enums.LeadStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 状态迁移入参（openapi StatusChangeRequest）。
 */
@Data
public class StatusChangeRequest {

    @NotNull(message = "目标状态不能为空")
    private LeadStatus toStatus;

    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;
}
