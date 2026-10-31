package com.leadinsight.module.lead.service.impl;

import com.leadinsight.common.util.TextUtils;
import com.leadinsight.module.lead.enums.LeadStatus;

/**
 * 状态迁移跟进内容装配（抽离自 LeadServiceImpl，保持单一职责与文件行数约束）。
 */
final class StatusChangeContent {

    private static final int CONTENT_MAX = 1000;

    private StatusChangeContent() {
    }

    static String build(LeadStatus from, LeadStatus to, String remark) {
        StringBuilder sb = new StringBuilder("状态由 ")
                .append(describe(from)).append(" 变更为 ").append(to.getLabel());
        if (!TextUtils.isBlank(remark)) {
            sb.append("；").append(remark.trim());
        }
        String content = sb.toString();
        return content.length() <= CONTENT_MAX ? content : content.substring(0, CONTENT_MAX);
    }

    static String describe(LeadStatus status) {
        return status == null ? "未知" : status.getLabel();
    }
}
