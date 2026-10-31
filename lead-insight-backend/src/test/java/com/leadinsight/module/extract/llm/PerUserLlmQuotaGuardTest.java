package com.leadinsight.module.extract.llm;

import com.leadinsight.module.extract.config.LlmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按账户额度守卫单测（成本红线，纯单测不起 Spring 上下文）。
 *
 * <p>覆盖（用户 2026-10-31 口径：每账户 10 次/日；用满即封「该账户 + 其来源 IP」一周）：</p>
 * <ul>
 *   <li>用满即封：第 N 次放行、第 N+1 次起封禁，账户与公网 IP 双双落库且解禁时间正确；</li>
 *   <li>IP 封锁跨账户生效：另一个账户从同一 IP 调用同样被拒（这才是封 IP 的意义）；</li>
 *   <li>本机/内网 IP 默认不落封禁（避免把开发演示机锁死一周），账户封禁不受影响；</li>
 *   <li>ban-days=0：只当天拒绝、不封禁；daily-limit=0：不限制；</li>
 *   <li>封禁到期自动解除且计数归零；</li>
 *   <li>并发：放行总数恰好等于上限（无 check-then-act 竞态）。</li>
 * </ul>
 */
class PerUserLlmQuotaGuardTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long ALICE = 1L;
    private static final long BOB = 2L;
    /** 公网地址（RFC 5737 文档保留段，不会指向真实主机） */
    private static final String PUBLIC_IP = "203.0.113.7";
    private static final String OTHER_PUBLIC_IP = "198.51.100.9";
    private static final String LOOPBACK = "127.0.0.1";

    /**
     * 计数表与监视器是静态的（防止多实例放大额度），因此每个用例前必须复位，
     * 否则上一个用例用光的额度会带进下一个用例。见 PerUserLlmQuotaGuard#resetInMemoryState。
     */
    @BeforeEach
    void resetGuardState() {
        PerUserLlmQuotaGuard.resetInMemoryState();
    }

    @Test
    @DisplayName("limit=3/ban=7：前 3 次放行，第 4 次起封禁；账户与公网 IP 同时落库，解禁=用满时刻+7 天")
    void reachesLimitThenBansAccountAndIp() {
        TestStore store = new TestStore();
        PerUserLlmQuotaGuard guard = guard(3, 7, false, store, fixedClock());

        assertTrue(guard.tryAcquire(ALICE, PUBLIC_IP).granted(), "第 1 次必须放行");
        assertTrue(guard.tryAcquire(ALICE, PUBLIC_IP).granted(), "第 2 次必须放行");
        assertTrue(guard.tryAcquire(ALICE, PUBLIC_IP).granted(), "第 3 次（用满）本身仍放行");

        LlmQuotaGuard.Result fourth = guard.tryAcquire(ALICE, PUBLIC_IP);
        assertFalse(fourth.granted(), "用满后第 4 次必须拒绝");
        assertEquals(LlmQuotaGuard.Decision.BANNED, fourth.decision(), "开禁时应判为 BANNED 而非单纯超额");
        assertNotNull(fourth.bannedUntil(), "封禁结果必须带解禁时间，否则用户无法知道何时恢复");
        assertEquals(LlmQuotaGuard.BanTarget.ACCOUNT, fourth.banTarget(), "是账户自己用满，文案才能说「你的额度已用满」");
        assertEquals(3, guard.usedToday(ALICE));

        LocalDateTime expected = LocalDateTime.ofInstant(Instant.parse("2026-01-01T00:00:00Z"), ZONE).plusDays(7);
        assertEquals(expected, store.bans.get(QuotaBanKey.ofUser(ALICE)),
                "账户封禁必须落库（跨重启存活），截止时间＝用满时刻+7 天");
        assertEquals(expected, store.bans.get(QuotaBanKey.ofIp(PUBLIC_IP)),
                "来源 IP 封禁必须同时落库——只封账户挡不住换号续刷");
    }

    @Test
    @DisplayName("IP 封禁跨账户生效：同 IP 换账户调用同样被拒，换 IP 的正常")
    void ipBanBlocksOtherAccountsOnSameIp() {
        TestStore store = new TestStore();
        PerUserLlmQuotaGuard guard = guard(2, 7, false, store, fixedClock());

        assertTrue(guard.tryAcquire(ALICE, PUBLIC_IP).granted());
        assertTrue(guard.tryAcquire(ALICE, PUBLIC_IP).granted());  // 用满 → 账户 + IP 被封

        LlmQuotaGuard.Result bobFromSameIp = guard.tryAcquire(BOB, PUBLIC_IP);
        assertFalse(bobFromSameIp.granted(), "换账户但同 IP 必须被拒，否则封 IP 没有意义");
        assertEquals(LlmQuotaGuard.Decision.BANNED, bobFromSameIp.decision());
        assertEquals(LlmQuotaGuard.BanTarget.IP, bobFromSameIp.banTarget(),
                "账户 2 自身一次都没用，被封是因为 IP——文案不能诬告它额度用满");

        assertTrue(guard.tryAcquire(BOB, OTHER_PUBLIC_IP).granted(), "不同 IP 的其他账户不应被牵连");
    }

    @Test
    @DisplayName("聚合阈值=3：同 IP 被封账户数达到 3 才封 IP——共享出口不连坐，一个人开三号仍被拦")
    void ipBanWaitsForThresholdToAvoidNatCollateral() {
        TestStore store = new TestStore();
        PerUserLlmQuotaGuard guard = guard(1, 7, false, 3, store, fixedClock());

        for (long uid = 1; uid <= 2; uid++) {
            assertTrue(guard.tryAcquire(uid, PUBLIC_IP).granted(), "第 " + uid + " 个账户首次抽取应放行");
            assertFalse(guard.tryAcquire(uid, PUBLIC_IP).granted(), "用满即封该账户");
            assertFalse(store.bans.containsKey(QuotaBanKey.ofIp(PUBLIC_IP)),
                    "已封 " + uid + " 个账户，未达阈值 3，不能封 IP——否则公司/校园 NAT 下的同事全被连坐");
        }

        assertTrue(guard.tryAcquire(3L, PUBLIC_IP).granted(), "第 3 个账户首抽仍放行（此时 IP 还没被封）");
        assertTrue(store.bans.containsKey(QuotaBanKey.ofIp(PUBLIC_IP)),
                "第 3 个账户用满后达到阈值，该 IP 才被封——一个人开三个号刷就是这种形态");

        LlmQuotaGuard.Result fourth = guard.tryAcquire(4L, PUBLIC_IP);
        assertFalse(fourth.granted(), "IP 已被封，新账户同样被拒");
        assertEquals(LlmQuotaGuard.BanTarget.IP, fourth.banTarget(), "第 4 个账户自己没用额度，被封原因是 IP");
    }

    @Test
    @DisplayName("ip-ban-min-accounts=0：显式关闭 IP 封禁，只封账户（共享出口整片连坐的兜底开关）")
    void ipBanDisabledWhenThresholdZero() {
        TestStore store = new TestStore();
        PerUserLlmQuotaGuard guard = guard(1, 7, false, 0, store, fixedClock());

        for (long uid = 1; uid <= 3; uid++) {
            assertTrue(guard.tryAcquire(uid, PUBLIC_IP).granted(), "账户首次抽取放行");
            assertFalse(guard.tryAcquire(uid, PUBLIC_IP).granted(), "用满即封该账户");
        }
        assertFalse(store.bans.containsKey(QuotaBanKey.ofIp(PUBLIC_IP)), "关闭后不得写任何 IP 封禁记录");
    }

    @Test
    @DisplayName("本机/内网 IP 默认不落封禁（避免把演示机锁一周），但账户封禁照常生效")
    void loopbackIpNotBannedByDefault() {
        TestStore store = new TestStore();
        PerUserLlmQuotaGuard guard = guard(2, 7, false, store, fixedClock());

        assertTrue(guard.tryAcquire(ALICE, LOOPBACK).granted());
        assertTrue(guard.tryAcquire(ALICE, LOOPBACK).granted());

        assertTrue(store.bans.containsKey(QuotaBanKey.ofUser(ALICE)), "账户必须被封");
        assertFalse(store.bans.containsKey(QuotaBanKey.ofIp(LOOPBACK)),
                "回环地址不得写 IP 封禁：开发演示/反代转发都是本机地址，封了等于锁死整台机器");
        assertTrue(guard.tryAcquire(BOB, LOOPBACK).granted(), "同机其他账户不受影响（因为没封 IP）");
    }

    @Test
    @DisplayName("ban-local-ip=true：显式放开后回环地址也会被封禁")
    void loopbackIpBannedWhenExplicitlyAllowed() {
        TestStore store = new TestStore();
        PerUserLlmQuotaGuard guard = guard(1, 7, true, store, fixedClock());

        assertTrue(guard.tryAcquire(ALICE, LOOPBACK).granted());   // 用满 → 账户 + 本机 IP 都被封
        assertTrue(store.bans.containsKey(QuotaBanKey.ofIp(LOOPBACK)), "放开开关后本机地址也应落封禁");
        assertFalse(guard.tryAcquire(BOB, LOOPBACK).granted(), "此时同机其他账户同样被拒");
    }

    @Test
    @DisplayName("ban-days=0：用满后只当天拒绝（DAILY_EXCEEDED），不落任何封禁；次日恢复")
    void banDaysZeroOnlyRejectsSameDay() {
        TestStore store = new TestStore();
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZONE);
        PerUserLlmQuotaGuard guard = guard(2, 0, false, store, clock);

        assertTrue(guard.tryAcquire(ALICE, PUBLIC_IP).granted());
        assertTrue(guard.tryAcquire(ALICE, PUBLIC_IP).granted());
        LlmQuotaGuard.Result third = guard.tryAcquire(ALICE, PUBLIC_IP);
        assertEquals(LlmQuotaGuard.Decision.DAILY_EXCEEDED, third.decision(), "未开自动封禁时应判为当日超额");
        assertTrue(store.bans.isEmpty(), "ban-days=0 不得写封禁记录");

        clock.plusDays(1);
        assertTrue(guard.tryAcquire(ALICE, PUBLIC_IP).granted(), "新的一天必须恢复额度");
        assertEquals(1, guard.usedToday(ALICE));
    }

    @Test
    @DisplayName("daily-limit=0：不限制开关生效，任意次都放行且不产生封禁")
    void zeroMeansUnlimited() {
        TestStore store = new TestStore();
        PerUserLlmQuotaGuard guard = guard(0, 7, false, store, fixedClock());

        for (int i = 0; i < 1_000; i++) {
            assertTrue(guard.tryAcquire(ALICE, PUBLIC_IP).granted(), "0 = 不限制，第 " + (i + 1) + " 次也必须放行");
        }
        assertTrue(store.bans.isEmpty(), "不限制模式下不得封禁任何对象");
    }

    @Test
    @DisplayName("封禁到期自动解除：跨日不解禁，满 7 天后解禁且计数归零")
    void banExpiresAfterConfiguredDays() {
        TestStore store = new TestStore();
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZONE);
        PerUserLlmQuotaGuard guard = guard(2, 7, false, store, clock);

        assertTrue(guard.tryAcquire(ALICE, PUBLIC_IP).granted());
        assertTrue(guard.tryAcquire(ALICE, PUBLIC_IP).granted());
        assertFalse(guard.tryAcquire(ALICE, PUBLIC_IP).granted(), "用满即封");

        clock.plusDays(1);
        assertFalse(guard.tryAcquire(ALICE, PUBLIC_IP).granted(),
                "封禁期内跨日不解禁——这正是与旧「每天重置」的差别");

        clock.plusDays(6); // 距封禁已满 7 天
        assertTrue(guard.tryAcquire(ALICE, PUBLIC_IP).granted(), "封禁到期后必须自动恢复，无需人工干预");
        assertEquals(1, guard.usedToday(ALICE), "解禁后当日计数从 0 重新开始累计");
    }

    @Test
    @DisplayName("并发：多线程争抢下放行总数恰好等于上限（无 check-then-act 竞态）")
    void concurrentAcquireNeverExceedsLimit() throws Exception {
        int limit = 100;
        TestStore store = new TestStore();
        PerUserLlmQuotaGuard guard = guard(limit, 7, false, store, fixedClock());

        int threads = 8;
        int perThread = 500; // 8 * 500 = 4000 次争抢，远超上限
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (guard.tryAcquire(ALICE, PUBLIC_IP).granted()) {
                            granted.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(limit, granted.get(), "并发下放行总数必须恰好等于上限，不多不少");
        assertEquals(limit, guard.usedToday(ALICE));
    }

    /** 默认：1 个账户用满即封该 IP（旧行为），用于验证「封 IP」这条链本身 */
    private PerUserLlmQuotaGuard guard(int limit, int banDays, boolean banLocalIp,
                                       QuotaBanStore store, Clock clock) {
        return guard(limit, banDays, banLocalIp, 1, store, clock);
    }

    private PerUserLlmQuotaGuard guard(int limit, int banDays, boolean banLocalIp,
                                       int ipBanMinAccounts, QuotaBanStore store, Clock clock) {
        LlmProperties properties = new LlmProperties();
        properties.getQuota().setDailyLimit(limit);
        properties.getQuota().setBanDays(banDays);
        properties.getQuota().setBanLocalIp(banLocalIp);
        properties.getQuota().setIpBanMinAccounts(ipBanMinAccounts);
        return new PerUserLlmQuotaGuard(properties, store, clock);
    }

    private Clock fixedClock() {
        return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZONE);
    }

    /** 内存封禁存储（单测专用，替代 llm_quota_ban 表） */
    private static final class TestStore implements QuotaBanStore {

        private final Map<String, LocalDateTime> bans = new HashMap<>();
        /** 账户封禁行留痕的来源 IP：账户 ID → IP（对应表里的 trigger_ip 列） */
        private final Map<String, String> triggerIps = new HashMap<>();

        @Override
        public Optional<LocalDateTime> activeBanUntil(String banKey, LocalDateTime now) {
            LocalDateTime until = bans.get(banKey);
            if (until == null) {
                return Optional.empty();
            }
            if (until.isAfter(now)) {
                return Optional.of(until);
            }
            bans.remove(banKey); // 与库实现一致：过期就地清理
            return Optional.empty();
        }

        @Override
        public void ban(String banKey, String subjectType, String subjectValue,
                        LocalDateTime until, int dailyUsed, String reason, String triggerIp) {
            bans.put(banKey, until);
            if (triggerIp != null) {
                triggerIps.put(subjectValue, triggerIp);
            }
        }

        @Override
        public int countBannedAccountsFromIp(String ip, LocalDateTime now) {
            return (int) triggerIps.entrySet().stream()
                    .filter(e -> ip.equals(e.getValue()))
                    .filter(e -> {
                        LocalDateTime until = bans.get(QuotaBanKey.ofUser(Long.parseLong(e.getKey())));
                        return until != null && until.isAfter(now);
                    })
                    .count();
        }

        @Override
        public void clear(String banKey) {
            bans.remove(banKey);
        }
    }

    /** 可推日期的时钟（单测专用，验证惰性跨日与封禁到期） */
    private static final class MutableClock extends Clock {

        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void plusDays(long days) {
            this.instant = this.instant.plusSeconds(days * 24 * 3600);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClock(instant, newZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
