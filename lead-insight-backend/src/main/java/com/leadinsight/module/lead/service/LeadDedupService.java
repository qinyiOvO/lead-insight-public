package com.leadinsight.module.lead.service;

/**
 * 内容指纹幂等去重（亮点3）。三层防护：
 * <ol>
 *   <li>内存缓存快路径（{@code lead:fingerprint:{sha256}}，putIfAbsent）</li>
 *   <li>数据库按指纹预查（可给出已存在线索 id，提示友好）</li>
 *   <li>MySQL 唯一索引 uk_lead_fingerprint 兜底（并发竞态下仍能拦截）</li>
 * </ol>
 * 归一化后文本不落库，仅保留 original_text 与 SHA-256 指纹。
 */
public interface LeadDedupService {

    /** 对原始正文执行 6 步归一化后取 SHA-256 小写十六进制指纹 */
    String fingerprint(String originalText);

    /**
     * 断言指纹未重复。命中则抛 40901（409），message 携带已存在线索 id。
     */
    void assertNotDuplicate(String fingerprint);

    /** 登记指纹到快路径缓存 */
    void register(String fingerprint, Long leadId);

    /**
     * 唯一键冲突兜底：按指纹回查已存在线索并抛 40901。
     * 用于并发下缓存与预查都未拦截、由唯一索引最终拦截的场景。
     */
    void resolveConflict(String fingerprint);
}
