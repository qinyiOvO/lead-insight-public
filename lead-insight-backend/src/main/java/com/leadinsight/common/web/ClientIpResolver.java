package com.leadinsight.common.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * 来源 IP 的解析与分类（纯函数，可单测；不做 DNS 查询）。
 *
 * <p><b>为什么默认不信任 X-Forwarded-For：</b>该头由客户端自带，任何人都能伪造。
 * 若直接采信，攻击者只要每次换一个 XFF 值就能绕过「按 IP 封禁」。因此只有在确认部署在
 * 可信反向代理之后时，才通过配置 {@code app.security.trust-forwarded-for=true} 打开。</p>
 *
 * <p><b>为什么区分公网/内网：</b>本机（127.0.0.1 / ::1）与内网地址（10./172.16-31./192.168.）
 * 往往是开发演示或反代转发后的地址。若对它们写 IP 封禁，一次额度用满就会把整台机器
 * （包括其他账户和联调）锁死一周。故默认只对<b>公网可路由地址</b>落 IP 封禁，账户封禁不受影响。</p>
 *
 * <p><b>为什么要能吃下 {@code ip:port}：</b>{@code remoteAddr} 在多数容器里只有 IP，但
 * 反代 / 部分 Valve / {@code Forwarded} 头（{@code for="[2001:db8::1]:1234"}）给出来的是
 * {@code 地址:端口}。旧实现用 {@code ^\d+\.\d+\.\d+\.\d+$} 硬卡，遇到带端口的形式直接判非法
 * 并退化成 {@code "unknown"}，于是 {@code banIp()} 拿到 null 静默跳过——<b>IP 封禁在真实
 * 请求下等于没生效，且没有任何报错</b>。这里改为显式解析端点，端口剥掉后只要 IP。</p>
 *
 * <p><b>端口为什么不进封禁键：</b>同一台机器每个 TCP 连接的源端口都不同（NAT 后更是天天变），
 * 用 {@code ip:port} 当键等于每次都换一个键，封不住任何人。端口只用于日志定位。</p>
 */
public final class ClientIpResolver {

    /** 无法判定时的占位值（会天然落到「不封 IP」分支，不会误封） */
    public static final String UNKNOWN = "unknown";

    /** IPv4 字面量骨架（每段 1-3 位数字，取值由 {@link #normalizeIpv4} 再卡 ≤255） */
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /**
     * IPv6 字面量字符集：仅十六进制数字、冒号、点（点用于 IPv4-mapped 形式 {@code ::ffff:1.2.3.4}）。
     * 先过字符集再交给 {@link InetAddress#getByName}，可保证它只做字面量解析、<b>不会触发 DNS</b>
     * （否则 {@code "example.com"} 这类输入会变成一次外部解析，既慢又是 SSRF 面）。
     */
    private static final Pattern IPV6_CHARSET = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    /** 端口段：1-5 位纯数字 */
    private static final Pattern PORT = Pattern.compile("^\\d{1,5}$");

    private ClientIpResolver() {
    }

    /**
     * 一个来源端点（IP + 端口）。端口不可知时为 0。
     *
     * @param ip   规范化后的 IP 字面量（不含端口，可直接当封禁键）
     * @param port 源端口（仅日志用；{@code remoteAddr} 不带端口时为 0）
     */
    public record Endpoint(String ip, int port) {

        /** 用于日志的可读形式：有端口写 {@code ip:port}，IPv6 加方括号避免歧义 */
        public String authority() {
            if (port <= 0) {
                return ip;
            }
            return (ip.indexOf(':') >= 0 ? "[" + ip + "]" : ip) + ":" + port;
        }
    }

    /**
     * 解析来源 IP。
     *
     * @param remoteAddr     servlet 的 remoteAddr（不可伪造，默认以此为准；允许带端口）
     * @param forwardedFor   X-Forwarded-For 原始值（可为多段逗号分隔）
     * @param realIp         X-Real-IP 原始值
     * @param trustForwarded 是否信任代理头
     * @return 规范化后的 IP 字面量（不含端口）；无法判定时返回 {@link #UNKNOWN}
     */
    public static String resolve(String remoteAddr, String forwardedFor, String realIp, boolean trustForwarded) {
        if (trustForwarded) {
            Endpoint first = parse(firstHop(forwardedFor));
            if (first != null) {
                return first.ip();
            }
            Endpoint real = parse(realIp);
            if (real != null) {
                return real.ip();
            }
        }
        Endpoint direct = parse(remoteAddr);
        return direct == null ? UNKNOWN : direct.ip();
    }

    /** X-Forwarded-For 的第一段（最靠近客户端的一跳） */
    private static String firstHop(String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return null;
        }
        int comma = forwardedFor.indexOf(',');
        String first = comma < 0 ? forwardedFor : forwardedFor.substring(0, comma);
        return first.trim();
    }

    /**
     * 解析「地址 / 地址:端口 / [IPv6]:端口 / 裸 IPv6」四种写法。
     *
     * <p>逐分支判定顺序（先精确后宽松，避免把非法串猜成合法 IP）：
     * <ol>
     *   <li>{@code [...]} —— 方括号 IPv6，括号后可跟 {@code :端口}；</li>
     *   <li>无冒号 —— 只可能是 IPv4（主机名一律拒收）；</li>
     *   <li>恰好一个冒号 —— {@code IPv4:端口}（纯 IPv6 不可能只有一个冒号）；</li>
     *   <li>多个冒号 —— 先整体当 IPv6；不成立再剥掉末尾 {@code :数字} 重试，
     *       覆盖「IPv6 直接拼端口」的非标准写法。</li>
     * </ol>
     *
     * @return 解析结果；非法返回 {@code null}（调用方据此决定是否退化为 UNKNOWN）
     */
    public static Endpoint parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > 64) {
            return null;
        }

        // ① [IPv6] 或 [IPv6]:port
        if (value.charAt(0) == '[') {
            int close = value.indexOf(']');
            if (close < 0) {
                return null;
            }
            String inner = value.substring(1, close);
            String rest = value.substring(close + 1);
            String ip = normalizeIpv6(inner);
            if (ip == null) {
                return null;
            }
            if (rest.isEmpty()) {
                return new Endpoint(ip, 0);
            }
            if (rest.charAt(0) != ':' || !PORT.matcher(rest.substring(1)).matches()) {
                return null;
            }
            return new Endpoint(ip, toPort(rest.substring(1)));
        }

        int firstColon = value.indexOf(':');

        // ② 无冒号：IPv4（或主机名 → 拒收）
        if (firstColon < 0) {
            String ip = normalizeIpv4(value);
            return ip == null ? null : new Endpoint(ip, 0);
        }

        // ③ 恰好一个冒号：IPv4:port
        if (firstColon == value.lastIndexOf(':')) {
            String ip = normalizeIpv4(value.substring(0, firstColon));
            String portPart = value.substring(firstColon + 1);
            if (ip == null || !PORT.matcher(portPart).matches()) {
                return null;
            }
            return new Endpoint(ip, toPort(portPart));
        }

        // ④ 多个冒号：先整体当 IPv6（如 ::1、2001:db8::1）
        String whole = normalizeIpv6(value);
        if (whole != null) {
            return new Endpoint(whole, 0);
        }
        // 再试「IPv6 + :port」的非标准写法（如 2001:db8::1:45231）
        int lastColon = value.lastIndexOf(':');
        String portPart = value.substring(lastColon + 1);
        if (!PORT.matcher(portPart).matches()) {
            return null;
        }
        String ip = normalizeIpv6(value.substring(0, lastColon));
        return ip == null ? null : new Endpoint(ip, toPort(portPart));
    }

    /** 是否是合法的 IP 字面量（不含主机名，允许带端口） */
    public static boolean isIpLiteral(String candidate) {
        return parse(candidate) != null;
    }

    /**
     * 是否是「本机或内网」地址——这类地址默认不写 IP 封禁（见类注释）。
     * 无法判定的输入按「本地」处理，取「宁可不封也不误封整台机器」的保守取向。
     */
    public static boolean isLocalOrPrivate(String ip) {
        Endpoint endpoint = parse(ip);
        if (endpoint == null) {
            return true;
        }
        try {
            InetAddress address = InetAddress.getByName(endpoint.ip());
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                return true;
            }
            byte[] raw = address.getAddress();
            // IPv6 唯一本地地址 fc00::/7
            return raw.length == 16 && (raw[0] & 0xFE) == 0xFC;
        } catch (UnknownHostException e) {
            return true;
        }
    }

    /** IPv4 规范化：骨架 + 每段 ≤255；非法返回 null */
    private static String normalizeIpv4(String value) {
        if (value == null || !IPV4.matcher(value).matches()) {
            return null;
        }
        for (String octet : value.split("\\.")) {
            if (Integer.parseInt(octet) > 255) {
                return null;
            }
        }
        return value;
    }

    /**
     * IPv6 规范化：先卡字符集（杜绝 DNS），再交给 {@link InetAddress} 做真正的语法校验。
     * 校验通过后原样返回（不压缩、不改写），保证同一个地址只有一种键写法。
     */
    private static String normalizeIpv6(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.length() < 2 || candidate.length() > 45
                || candidate.indexOf(':') < 0
                || !IPV6_CHARSET.matcher(candidate).matches()) {
            return null;
        }
        try {
            InetAddress.getByName(candidate); // 字符集已限定为字面量，不会触发 DNS
            return candidate;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** 端口串 → int；>65535 视作未知（0），不让脏数据污染日志 */
    private static int toPort(String portPart) {
        int port = Integer.parseInt(portPart);
        return port <= 65535 ? port : 0;
    }
}
