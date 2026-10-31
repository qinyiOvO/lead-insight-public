package com.leadinsight.module.lead.service.impl;

import com.leadinsight.common.exception.BizException;
import com.leadinsight.common.util.TextUtils;
import com.leadinsight.module.lead.dto.LeadCreateRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手工录入模式跨字段校验（AC-08）：客户名称与需求要点必填，逐字段返回错误。
 * 抽离自 LeadServiceImpl 以保持单一职责与文件行数约束。
 */
@Component
public class ManualModeValidator {

    public void validate(LeadCreateRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (TextUtils.isBlank(request.getCustomerName())) {
            errors.put("customerName", "客户名称不能为空");
        }
        if (TextUtils.isBlank(request.getDemandSummary())) {
            errors.put("demandSummary", "需求要点不能为空");
        }
        if (request.getBudgetAmount() != null && request.getBudgetAmount().signum() < 0) {
            errors.put("budgetAmount", "预算金额不能为负数");
        }
        if (!errors.isEmpty()) {
            throw BizException.fieldErrors(errors);
        }
    }
}
