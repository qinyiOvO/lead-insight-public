package com.leadinsight.module.lead.dto;

import com.leadinsight.module.lead.enums.IntentCategory;
import com.leadinsight.module.lead.enums.LeadStatus;
import lombok.Data;

/**
 * 线索列表查询入参（openapi GET /api/v1/leads 的 query 参数）。
 * 默认：page=1 limit=20 sort=priority_score order=desc。
 */
@Data
public class LeadQueryRequest {

    private Integer page = 1;

    private Integer limit = 20;

    /** priority_score | created_at */
    private String sort = "priority_score";

    /** asc | desc */
    private String order = "desc";

    private LeadStatus status;

    private IntentCategory intentCategory;

    private Long ownerId;

    /** 关键词：按客户名/公司名/需求要点三字段 OR 模糊匹配（AC-11） */
    private String q;
}
