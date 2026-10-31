package com.leadinsight.module.extract.llm;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 额度封禁的持久化抽象（成本红线的「封禁」半边，对象可为账户或来源 IP）。
 *
 * <p>抽成接口有两个原因：① 封禁必须跨重启存活，落库是实现细节而非业务规则；
 * ② 单测用内存实现即可验证守卫逻辑，不牵数据库。</p>
 */
public interface QuotaBanStore {

    /**
     * 查询该封禁键当前是否处于封禁期。
     *
     * @param banKey {@link QuotaBanKey} 生成的键（USER:1 / IP:1.2.3.4）
     * @return 封禁截止时间；无有效封禁返回空
     */
    Optional<LocalDateTime> activeBanUntil(String banKey, LocalDateTime now);

    /**
     * 写入/覆盖封禁。
     *
     * @param banKey       {@link QuotaBanKey} 生成的键
     * @param subjectType  USER / IP（便于按类型统计与排查）
     * @param subjectValue 账户 ID 或 IP 字面量
     * @param until        封禁截止时间
     * @param dailyUsed    触发当日已用次数（留痕用）
     * @param reason       原因摘要（禁止写入密钥或邮件正文）
     * @param triggerIp    触发本次封禁的来源 IP；封 IP 自身这条记录传 {@code null}
     */
    void ban(String banKey, String subjectType, String subjectValue,
             LocalDateTime until, int dailyUsed, String reason, String triggerIp);

    /**
     * 统计该 IP 上「当前仍被封禁」的<b>不同账户</b>数。
     *
     * <p>只有达到 {@code llm.quota.ip-ban-min-accounts} 才封该 IP：区分
     * 「一个人开多个号刷」与「一栋楼共用一个出口 NAT」，后者不该被一个人连坐。</p>
     *
     * @param ip  来源 IP 字面量
     * @param now 当前时间（用于过滤已过期记录）
     */
    int countBannedAccountsFromIp(String ip, LocalDateTime now);

    /** 清除该封禁键的记录（到期后调用，避免历史行无意义堆积） */
    void clear(String banKey);
}
