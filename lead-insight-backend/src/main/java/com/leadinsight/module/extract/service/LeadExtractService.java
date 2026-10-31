package com.leadinsight.module.extract.service;

import com.leadinsight.module.lead.entity.Lead;

/**
 * 线索抽取编排（亮点1 + 亮点4，T2-02/03/04）。
 *
 * <p>职责边界：本服务只<b>读</b>传入线索的 {@code originalText}，在内存中回填抽取字段与
 * 终态，并落自己的两张表（lead_extract_result / model_call_log）；
 * <b>不写 lead 行、不评分</b>——lead 的持久化与评分归属 lead 模块（分层铁律：跨模块走 service）。</p>
 *
 * <p>铁律：本方法<b>永不抛异常</b>——任何失败都折算为线索上的 FAILED / DEGRADED 终态，
 * 粘贴创建主流程不受影响（AC-28/29 机制基础）。</p>
 */
public interface LeadExtractService {

    /**
     * 执行一次抽取（同步，含限流/熔断/重试/置信度自算），并把抽取字段与终态回填到传入的
     * lead 对象（调用方负责持久化与评分）。
     *
     * @param lead       已含 originalText 的线索（新建或重抽取），抽取结果直接写回该对象
     * @param operatorId 触发本次抽取的账户 ID（{@code sys_user.id}）——按账户计成本额度与封禁，
     *                   批量导入时传任务创建人；不可为 null 由调用方保证，内部对空值归入匿名桶 0
     * @param clientIp   触发本次抽取的来源 IP（用于「用满即封 IP」）；非 HTTP 线程传 null
     * @return 终态与可读文案
     */
    ExtractOutcome extract(Lead lead, Long operatorId, String clientIp);

    /**
     * 当前运行环境是否已配置大模型 API Key（J-1 分轨判断：无 key 走同步秒回 DEGRADED，
     * 有 key 走 WAITING + 异步抽取）。只读配置，无副作用。
     */
    boolean isModelConfigured();
}
