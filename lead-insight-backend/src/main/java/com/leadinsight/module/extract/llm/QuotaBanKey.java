package com.leadinsight.module.extract.llm;

/**
 * 额度封禁键（成本红线：封禁可落在「账户」或「来源 IP」两种对象上）。
 *
 * <p>两种对象用同一个前缀化字符串主键共存于一张表（{@code llm_quota_ban.ban_key}），
 * 好处是查询/清理逻辑只有一套，不因新增对象类型而改表结构。</p>
 *
 * <p>为什么要同时封 IP：账户可以注册/共用，仅封账户时换个账号就能继续刷；
 * 而「同一来源 IP 一周内不允许再消耗额度」把换号的成本抬高到换网络环境。</p>
 */
public final class QuotaBanKey {

    public static final String TYPE_USER = "USER";
    public static final String TYPE_IP = "IP";

    private static final String USER_PREFIX = TYPE_USER + ":";
    private static final String IP_PREFIX = TYPE_IP + ":";

    private QuotaBanKey() {
    }

    /** 账户封禁键：USER:12 */
    public static String ofUser(long userId) {
        return USER_PREFIX + userId;
    }

    /** IP 封禁键：IP:203.0.113.7 */
    public static String ofIp(String clientIp) {
        return IP_PREFIX + clientIp;
    }
}
