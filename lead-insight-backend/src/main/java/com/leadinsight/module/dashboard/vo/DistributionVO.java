package com.leadinsight.module.dashboard.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分布条目出参（openapi 1.3.1 DistributionVO）。
 *
 * <p>key=枚举名（如 NEW / CONSULT），label=中文名称（如 新线索 / 咨询），
 * value=计数。状态分布固定覆盖 LeadStatus 四值、意图分布固定覆盖 IntentCategory
 * 五值——零计数项也返回，保证前端图表 x 轴稳定。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DistributionVO {

    private String key;

    private String label;

    private Long value;
}
