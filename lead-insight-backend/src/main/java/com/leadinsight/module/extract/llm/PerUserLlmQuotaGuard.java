package com.leadinsight.module.extract.llm;

import com.leadinsight.common.web.ClientIpResolver;
import com.leadinsight.module.extract.config.LlmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 按账户的抽取额度守卫（成本红线，用户 2026-10-31 口径）。
 *
 * <p><b>规则：</b>每账户每自然日最多 {@code llm.quota.daily-limit}（默认 10）次抽取；
 * 用满即把<b>该账户</b>封禁 {@code llm.quota.ban-days}（默认 7 天）。
 * 来源 IP 不是立刻连坐：只有当同一 IP 上<b>已被封禁的不同账户数达到
 * {@code llm.quota.ip-ban-min-accounts}（默认 3）</b>时才封该 IP——一个人开三个号刷会被拦下，
 * 而一栋楼共用一个出口 NAT 时，一个人的失控不会让整片出口一起失联。</p>
 *
 * <p><b>为什么计数在内存、封禁在库：</b>计数随自然日翻篇，重启丢一点计数无实际损失
 * （能重启进程的是运维，不是攻击者）；而封禁必须跨重启存活，否则「重启即解封」，
 * 这条红线等于没有。故封禁走 {@link QuotaBanStore} 落库。</p>
 *
 * <p><b>并发正确性：</b>「判日期 / 判封禁 / 判断是否达上限 / 自增 / 触发封禁」放在同一监视器内，
 * 保证并发下放行次数<b>恰好不超过</b>上限（对齐 {@link TokenBucketRateLimiter} 的 check-then-act 防竞态做法）。
 * 监视器内含两次主键查询，代价是同账户串行；按每账户每日 10 次的量级可忽略。</p>
 *
 * <p><b>监视器与计数为什么都是 {@code static}：</b>本类虽由 Spring 以单例装配，但一旦将来
 * 出现第二个实例（被代理包一层、手工 {@code new}、或 Spring 上下文重建），实例级锁会让
 * <b>两个实例各自加自己的锁、各自累加自己的计数</b>——互斥失效，且同一账户在一天内能拿到
 * 2× 额度，正是这条成本红线最不能被击穿的地方。故锁 {@link #LOCK} 与计数表 {@link #USAGES}、
 * 去重告警表 {@link #WARNED_ON} 全部提升为静态：只要 JVM 里有一个 class，就只有一个真相。
 * 代价是单测需要显式调用 {@link #resetInMemoryState()} 清理静态态，见该类测试的 {@code @BeforeEach}。</p>
 *
 * <p><b>无 key 铁律：</b>本组件与 api-key 是否为空无关——无 key 时不会走到这里，日志也绝不打印 key。</p>
 */
@Slf4j
@Component
public class PerUserLlmQuotaGuard implements LlmQuotaGuard {

    private static final DateTimeFormatter BAN_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final int dailyLimit;
    private final int banDays;
    private final boolean banLocalIp;
    private final int ipBanMinAccounts;
    private final Clock clock;
    private final QuotaBanStore banStore;

    /**
     * 全局监视器：见类注释——静态是为了让「多实例」也无法放大额度。
     */
    private static final Object LOCK = new Object();
    /** 账户 ID → 当日用量（仅本进程有效；日期翻篇即归零），受 {@link #LOCK} 保护 */
    private static final Map<Long, Usage> USAGES = new HashMap<>();
    /** 已就「额度用尽/被封」告警过的对象，避免同一天重复刷日志，受 {@link #LOCK} 保护 */
    private static final Map<String, LocalDate> WARNED_ON = new HashMap<>();

    /**
     * Spring 装配构造。
     *
     * <p><b>必须标 {@code @Autowired}：</b>本类有多个构造器，Spring 仅在「恰好一个构造器」时才自动选用；
     * 不标注会退回要求无参构造器，进而抛 {@code NoSuchMethodException} 导致启动即崩
     * （该坑曾在同类组件上发生过，见 LeadInsightApplicationContextTest 的注释）。</p>
     */
    @Autowired
    public PerUserLlmQuotaGuard(LlmProperties properties, QuotaBanStore banStore) {
        this(properties, banStore, Clock.systemDefaultZone());
    }

    /** 可注入时钟的构造（单测用于推进日期，验证跨日重置与封禁到期） */
    PerUserLlmQuotaGuard(LlmProperties properties, QuotaBanStore banStore, Clock clock) {
        this.dailyLimit = properties.getQuota().getDailyLimit();
        this.banDays = properties.getQuota().getBanDays();
        this.banLocalIp = properties.getQuota().isBanLocalIp();
        this.ipBanMinAccounts = properties.getQuota().getIpBanMinAccounts();
        this.banStore = banStore;
        this.clock = clock;
    }

    @Override
    public Result tryAcquire(Long userId, String clientIp) {
        long uid = (userId == null || userId <= 0) ? 0L : userId;
        String ip = normalizeIp(clientIp);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = LocalDate.now(clock);

        synchronized (LOCK) {
            // ① 封禁优先：账户封禁 或 来源 IP 封禁，任一命中即拒绝（连计数都不做）
            if (banDays > 0) {
                Optional<LocalDateTime> userBan = banStore.activeBanUntil(QuotaBanKey.ofUser(uid), now);
                if (userBan.isPresent()) {
                    warnOnce(QuotaBanKey.ofUser(uid), today,
                            "【成本红线】账户 {} 处于额度封禁期，拒绝消耗模型额度：解禁时间 {}",
                            uid, userBan.get().format(BAN_TIME));
                    return Result.accountBanned(userBan.get());
                }
                if (ip != null) {
                    Optional<LocalDateTime> ipBan = banStore.activeBanUntil(QuotaBanKey.ofIp(ip), now);
                    if (ipBan.isPresent()) {
                        warnOnce(QuotaBanKey.ofIp(ip), today,
                                "【成本红线】来源 IP {} 处于额度封禁期，拒绝消耗模型额度：解禁时间 {}（账户 {} 同样被拒）",
                                ip, ipBan.get().format(BAN_TIME), uid);
                        return Result.ipBanned(ipBan.get());
                    }
                }
            }

            if (dailyLimit <= 0) {
                return Result.allowed(); // 0 或负数 = 不限制
            }

            Usage usage = USAGES.computeIfAbsent(uid, k -> new Usage(today));
            if (!usage.date.equals(today)) {
                usage.date = today;
                usage.used = 0;
            }

            if (usage.used >= dailyLimit) {
                // 只可能在 banDays <= 0（不封禁）或封禁写库失败时走到这里
                warnOnce(QuotaBanKey.ofUser(uid), today,
                        "【成本红线】账户 {} 今日抽取额度已用尽：已用 {} / 上限 {}（日期 {}），"
                                + "后续抽取将降级为待人工处理", uid, usage.used, dailyLimit, today);
                return Result.dailyExceeded();
            }

            usage.used++;

            // ② 用满即封账户：本次调用照常放行，从下一次调用起该账户进入封禁期。
            //    来源 IP 只记留痕（trigger_ip），是否连坐由阈值判定，见 banIp。
            if (banDays > 0 && usage.used >= dailyLimit) {
                LocalDateTime until = now.plusDays(banDays);
                String reason = "单日用满 " + dailyLimit + " 次抽取额度";
                banStore.ban(QuotaBanKey.ofUser(uid), QuotaBanKey.TYPE_USER, String.valueOf(uid),
                        until, usage.used, reason, ip);
                boolean ipBanned = banIp(ip, until, usage.used, now);
                log.warn("【成本红线】账户 {} 单日额度用满（{} 次），已禁止其消耗模型额度至 {}（{} 天）；来源 IP {} {}",
                        uid, dailyLimit, until.format(BAN_TIME), banDays,
                        ip == null ? "-" : ip, ipBanned ? "同步封禁" : "未封禁（本机/内网地址、非 HTTP 线程，或同 IP 被封账户数未达阈值）");
            }

            return Result.allowed();
        }
    }

    /** 当日已用额度（观测/测试用，只读快照） */
    int usedToday(Long userId) {
        long uid = (userId == null || userId <= 0) ? 0L : userId;
        synchronized (LOCK) {
            Usage usage = USAGES.get(uid);
            if (usage == null || !usage.date.equals(LocalDate.now(clock))) {
                return 0;
            }
            return usage.used;
        }
    }

    /**
     * 清空进程内状态。
     *
     * <p>监控器与计数表已提升为静态（见类注释），所以状态是<b>全 JVM 共享</b>的：任何测试
     * 用光某账户额度后，同 JVM 里的下一个测试会直接继承「已超额」。故本方法公开给测试层，
     * 由各测试的 {@code @BeforeEach} 显式复位（当前调用点：PerUserLlmQuotaGuardTest、
     * LeadExtractPipelineTest）。</p>
     *
     * <p><b>生产代码不得调用</b>：清了计数等于白送额度。它也<b>没有</b>暴露成任何 HTTP 端点。</p>
     */
    public static void resetInMemoryState() {
        synchronized (LOCK) {
            USAGES.clear();
            WARNED_ON.clear();
        }
    }

    /**
     * 是否对该 IP 落封禁。
     *
     * <p>四条否决，依次判断：① 无 IP（非 HTTP 线程）；② 本机/内网地址且未显式允许
     * （否则开发演示时一次用满就把整台机器锁一周）；③ {@code ip-ban-min-accounts <= 0}（显式关闭 IP 封禁）；
     * ④ <b>同 IP 已被封账户数不足阈值</b>——这条就是为了不牵连共享出口的其他人：
     * 公司/校园 NAT 下一个人用满，他只连累自己；而一个人开三个号刷，三个账户都落在同一 trigger_ip 上，
     * 第 3 次就会把该 IP 封掉。</p>
     *
     * @param now 当前时间（统计阈值时用于排除已过期记录）
     */
    private boolean banIp(String ip, LocalDateTime until, int used, LocalDateTime now) {
        if (ip == null) {
            return false;
        }
        if (!banLocalIp && ClientIpResolver.isLocalOrPrivate(ip)) {
            return false;
        }
        if (ipBanMinAccounts <= 0) {
            return false;
        }
        int bannedAccounts = 0;
        if (ipBanMinAccounts > 1) {
            bannedAccounts = banStore.countBannedAccountsFromIp(ip, now);
            if (bannedAccounts < ipBanMinAccounts) {
                log.info("【成本红线】来源 IP {} 同 IP 已被封账户数 {}/{}，未达阈值，本次不封该 IP（避免牵连共享出口）",
                        ip, bannedAccounts, ipBanMinAccounts);
                return false;
            }
        }
        String reason = ipBanMinAccounts > 1
                ? "同一 IP 上已有 " + bannedAccounts + " 个账户用满额度（阈值 " + ipBanMinAccounts + "）"
                : "单日用满 " + used + " 次抽取额度";
        banStore.ban(QuotaBanKey.ofIp(ip), QuotaBanKey.TYPE_IP, ip, until, used, reason, null);
        return true;
    }

    /**
     * 归一化来源地址：只保留 IP（剥掉端口、拒收主机名与 {@code unknown}）。
     *
     * <p>即便调用方直接递来 {@code remoteAddr}（可能带端口），封禁键也不会被污染——
     * 同一个 IP 必须始终映射到同一个 {@code IP:...} 键，否则换了端口就换个键，谁都封不住。</p>
     */
    private String normalizeIp(String clientIp) {
        ClientIpResolver.Endpoint endpoint = ClientIpResolver.parse(clientIp);
        return endpoint == null ? null : endpoint.ip();
    }

    /** 同一对象同一自然日只告警一次，避免刷日志 */
    private void warnOnce(String key, LocalDate today, String pattern, Object... args) {
        if (today.equals(WARNED_ON.get(key))) {
            return;
        }
        WARNED_ON.put(key, today);
        log.warn(pattern, args);
    }

    /** 单账户当日用量（可变，受 lock 保护） */
    private static final class Usage {
        private LocalDate date;
        private int used;

        private Usage(LocalDate date) {
            this.date = date;
            this.used = 0;
        }
    }
}
