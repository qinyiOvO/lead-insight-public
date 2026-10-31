/**
 * 单条粘贴导入的抽取草稿视图模型（T2-05）。
 *
 * 注意：openapi 1.2.2 只有「整单」抽取置信度（LeadVO.extractConfidence / confidenceLevel），
 * 未提供字段级置信度，也没有「识别 -> 确认 -> 保存」的两步预览端点。
 * 因此本视图模型以「字段是否抽取到（missing）」作为字段级「待复核」的判定依据，
 * 置信度徽章暂以整单档位标注；契约补充字段级置信度后仅需替换该判定来源。
 */

export type ExtractFieldKind = 'text' | 'textarea' | 'number' | 'date' | 'intent' | 'scale';

export interface ExtractDraftField {
  /** 对应 openapi LeadCreateRequest / LeadUpdateRequest 字段名（保存时按 key 回填） */
  key: string;
  label: string;
  kind: ExtractFieldKind;
  value: string | number | null;
  /** 抽取阶段该字段是否未抽取到（null/空串）-> 行级「待复核」高亮 */
  missing: boolean;
  placeholder?: string;
  /** 业务必填（AC-08：客户名称 / 需求要点） */
  required?: boolean;
}

export interface ExtractFieldOption {
  value: string;
  label: string;
}
