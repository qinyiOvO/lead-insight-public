package com.leadinsight.common.web;

/**
 * 请求来源 IP 的线程内传递（成本红线：按 IP 封禁需要知道「谁在调」）。
 *
 * <p>为什么用 ThreadLocal 而不是层层传参：IP 是 HTTP 层信息，若沿 service 签名一路下传，
 * 会把「Web 细节」污染到业务接口；而抽取编排在多处被调用（单条/重抽/批量/异步）。
 * 由 {@link com.leadinsight.common.web.ClientIpFilter} 在请求进入时写入、请求结束时清除，
 * 业务侧只在本线程内读取一次并随「触发者 ID」一起下传，异步任务里用捕获到的值，不依赖线程复用。</p>
 */
public final class ClientIpContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ClientIpContext() {
    }

    /** 当前请求的来源 IP；非 Web 线程（如定时任务）返回 null */
    public static String current() {
        return CURRENT.get();
    }

    public static void set(String clientIp) {
        CURRENT.set(clientIp);
    }

    /** 请求结束必须清除，避免线程池复用时把上一个请求的 IP 泄漏给下一个请求 */
    public static void clear() {
        CURRENT.remove();
    }
}
