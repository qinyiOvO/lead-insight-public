package com.leadinsight.module.extract.llm;

import java.time.LocalDateTime;

/**
 * 按账户的抽取额度闸（成本红线，DeepSeek 预付费余额制的应用层早停闸）。
 *
 * <p>与 {@link RateLimiter}（限制<b>速率</b>：每秒调用数）互补——本抽象限制的是
 * <b>用量</b>：每账户每自然日最多允许多少次抽取，以及用满后对「账户 + 来源 IP」的封禁。
 * 二者缺一不可：只封速率时，一个已登录用户可持续跑满 1 QPS = 8.6 万次/天，余额可被无声刷空。</p>
 *
 * <p><b>计量规则（用户 2026-10-31 口径）：</b></p>
 * <ul>
 *   <li>每个<b>账户</b>每日 10 次（{@code llm.quota.daily-limit}），不同账户互不挤占；</li>
 *   <li>单日用满即把<b>该账户</b>与<b>触发时的来源 IP</b>一并禁止消耗模型额度
 *       {@code llm.quota.ban-days} 天（默认 7 天）；</li>
 *   <li>只封账户挡不住换号续刷，故同时封 IP；本机/内网地址默认不落 IP 封禁
 *       （避免开发演示时把整台机器锁死），详见 {@link com.leadinsight.common.web.ClientIpResolver}。</li>
 * </ul>
 *
 * <p><b>语义：</b>调用方在<b>真正调用模型之前</b>申请 1 个额度（1 次 {@code extract()} = 1 个额度）。
 * 未获批表示不能调模型，调用方走 DEGRADED 降级（线索照常落库、主流程不中断），
 * <b>永不抛异常</b>。计数在进程内、封禁落库（换实现即可切 Redis，业务零改动）。</p>
 */
public interface LlmQuotaGuard {

    /**
     * 申请 1 个当日额度（不阻塞、不抛异常）。
     *
     * @param userId   触发本次抽取的账户 ID（{@code sys_user.id}）；为空时归入匿名桶 0
     * @param clientIp 触发本次抽取的来源 IP；为空表示非 HTTP 线程，此时不做 IP 封禁
     * @return 裁决结果：{@link Result#granted()} 为 true 才可调用模型
     */
    Result tryAcquire(Long userId, String clientIp);

    /** 裁决结果。{@code bannedUntil} 仅在 {@link Decision#BANNED} 时有值，用于给用户提示解禁时间。 */
    record Result(Decision decision, LocalDateTime bannedUntil, BanTarget banTarget) {

        /** 静态工厂：获批（与实例方法 {@link #granted()} 重名会与 record 的访问器冲突，故用 allowed 命名） */
        public static Result allowed() {
            return new Result(Decision.GRANTED, null, null);
        }

        public static Result dailyExceeded() {
            return new Result(Decision.DAILY_EXCEEDED, null, null);
        }

        /** 该账户自己用满了额度而被封 */
        public static Result accountBanned(LocalDateTime bannedUntil) {
            return new Result(Decision.BANNED, bannedUntil, BanTarget.ACCOUNT);
        }

        /** 该账户尚未用满，但来源 IP 已被别的账户拖入封禁期 */
        public static Result ipBanned(LocalDateTime bannedUntil) {
            return new Result(Decision.BANNED, bannedUntil, BanTarget.IP);
        }

        public boolean granted() {
            return decision == Decision.GRANTED;
        }
    }

    /**
     * 封禁对象。
     *
     * <p><b>为什么要带出来：</b>被封的可能是账户自己，也可能是账户没用满、只是和「刷满的人」
     * 共用了同一个 IP。两者对用户是两件完全不同的事——若都提示「你的额度已用满」，
     * 一个今天一次都没用过的账号会被告知它已用满，用户只能去找客服。文案必须区分。</p>
     */
    enum BanTarget {
        /** 账户自身用满额度 */
        ACCOUNT,
        /** 来源 IP 被牵连封禁（账户自身可能一次都没用） */
        IP
    }

    /** 裁决类型 */
    enum Decision {
        /** 获批，可调用模型 */
        GRANTED,
        /** 当日额度已用尽且未开自动封禁（仅当 ban-days <= 0 时出现） */
        DAILY_EXCEEDED,
        /** 该账户或其来源 IP 处于封禁期，禁止消耗模型额度 */
        BANNED
    }
}
