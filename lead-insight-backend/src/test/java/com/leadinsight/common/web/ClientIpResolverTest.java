package com.leadinsight.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 来源 IP 解析单测。
 *
 * <p>回归重点：旧实现用 {@code ^\d+\.\d+\.\d+\.\d+$} 硬卡 {@code remoteAddr}，
 * 遇到 {@code 203.0.113.7:45231}（带端口）或 {@code ::1}（以冒号开头）都判非法，
 * 退化成 {@code "unknown"} → {@code banIp()} 拿到 null 静默跳过，<b>IP 封禁在真实请求下不生效且无报错</b>。
 * 这里把每种真实出现过的地址写法都钉死。</p>
 */
class ClientIpResolverTest {

    /** 公网地址（RFC 5737 文档保留段，不会指向真实主机） */
    private static final String PUBLIC_IP = "203.0.113.7";

    @Test
    @DisplayName("IPv4 带端口：剥掉端口只留 IP（旧实现在这里退化成 unknown，IP 封禁静默失效）")
    void parsesIpv4WithPort() {
        ClientIpResolver.Endpoint endpoint = ClientIpResolver.parse(PUBLIC_IP + ":45231");
        assertNotNull(endpoint, "ip:port 必须能解析——这是反代/Valve 下的常见形态");
        assertEquals(PUBLIC_IP, endpoint.ip(), "封禁键只能取纯 IP，端口不能混进去");
        assertEquals(45231, endpoint.port());

        assertEquals(PUBLIC_IP, ClientIpResolver.resolve(PUBLIC_IP + ":45231", null, null, false),
                "resolve 也必须给出纯 IP，而不是 unknown");
    }

    @Test
    @DisplayName("同一 IP 的不同源端口归一到同一个封禁键（否则每个连接都是新键，封不住人）")
    void sameIpDifferentPortsShareBanKey() {
        assertEquals(ClientIpResolver.parse(PUBLIC_IP + ":1001").ip(),
                ClientIpResolver.parse(PUBLIC_IP + ":65000").ip());
    }

    @Test
    @DisplayName("IPv6 回环 ::1：旧实现因「冒号必须在 0 之后」直接判非法，现应正常识别")
    void parsesIpv6Loopback() {
        ClientIpResolver.Endpoint endpoint = ClientIpResolver.parse("::1");
        assertNotNull(endpoint, "::1 是合法 IPv6，不能被当成非法串");
        assertEquals("::1", endpoint.ip());
        assertTrue(ClientIpResolver.isLocalOrPrivate("::1"), "IPv6 回环必须判为本地，默认不封");
    }

    @Test
    @DisplayName("方括号 IPv6（可带端口）：[2001:db8::1] 与 [2001:db8::1]:45231")
    void parsesBracketedIpv6() {
        ClientIpResolver.Endpoint bare = ClientIpResolver.parse("[2001:db8::1]");
        assertNotNull(bare);
        assertEquals("2001:db8::1", bare.ip());
        assertEquals(0, bare.port());

        ClientIpResolver.Endpoint withPort = ClientIpResolver.parse("[2001:db8::1]:45231");
        assertNotNull(withPort);
        assertEquals("2001:db8::1", withPort.ip());
        assertEquals(45231, withPort.port());
        assertEquals("[2001:db8::1]:45231", withPort.authority(), "日志形式要给 IPv6 补方括号，避免 ip:port 歧义");
    }

    @Test
    @DisplayName("非标准写法「IPv6 直接拼端口」也能剥出 IP；整体可解析成 IPv6 时优先当地址")
    void parsesIpv6WithRawPort() {
        ClientIpResolver.Endpoint endpoint = ClientIpResolver.parse("2001:db8::1:45231");
        assertNotNull(endpoint);
        assertEquals("2001:db8::1", endpoint.ip(), "45231 是 5 位十六进制，整体不可能是合法 IPv6，故按端口剥掉");

        // 歧义说明：2001:db8::1:443 本身就是合法 IPv6（末段 0x443），只能整体当地址。
        // 标准写法（带端口）是 [2001:db8::1]:443，上面的用例已覆盖。
        assertEquals("2001:db8::1:443", ClientIpResolver.parse("2001:db8::1:443").ip());
    }

    @Test
    @DisplayName("主机名一律拒收：绝不把字符串交给解析器做 DNS（避免外部解析与 SSRF 面）")
    void rejectsHostnames() {
        assertNull(ClientIpResolver.parse("evil.example.com"));
        assertNull(ClientIpResolver.parse("localhost"));
        assertEquals(ClientIpResolver.UNKNOWN, ClientIpResolver.resolve("evil.example.com", null, null, false),
                "识别不出就老实给 unknown，不能猜");
        assertTrue(ClientIpResolver.isLocalOrPrivate("evil.example.com"),
                "无法判定按「本地」处理：宁可不封，也不能误封整台机器");
    }

    @Test
    @DisplayName("非法数值与畸形串：越界八位组、空串、超长、非数字端口")
    void rejectsMalformed() {
        assertNull(ClientIpResolver.parse("999.1.1.1"));
        assertNull(ClientIpResolver.parse("203.0.113"));
        assertNull(ClientIpResolver.parse(""));
        assertNull(ClientIpResolver.parse("   "));
        assertNull(ClientIpResolver.parse(null));
        assertNull(ClientIpResolver.parse(PUBLIC_IP + ":abc"));
        assertNull(ClientIpResolver.parse("[2001:db8::1]:"));
        assertNull(ClientIpResolver.parse("[" + PUBLIC_IP));
    }

    @Test
    @DisplayName("端口越界（>65535）不再当端口，避免脏数据污染日志")
    void outOfRangePortTreatedAsUnknown() {
        ClientIpResolver.Endpoint endpoint = ClientIpResolver.parse(PUBLIC_IP + ":99999");
        assertNotNull(endpoint);
        assertEquals(PUBLIC_IP, endpoint.ip());
        assertEquals(0, endpoint.port());
    }

    @Test
    @DisplayName("trust-forwarded-for=false：伪造的 XFF / X-Real-IP 一律不采信，仍用 remoteAddr")
    void ignoresForwardedHeadersByDefault() {
        assertEquals(PUBLIC_IP,
                ClientIpResolver.resolve(PUBLIC_IP, "1.2.3.4", "5.6.7.8", false),
                "默认不信任代理头——否则攻击者换个 XFF 就能绕过 IP 封禁");
    }

    @Test
    @DisplayName("trust-forwarded-for=true：取 XFF 第一跳；第一跳非法时退回 X-Real-IP，再退回 remoteAddr")
    void honoursForwardedHeadersWhenTrusted() {
        assertEquals("1.2.3.4",
                ClientIpResolver.resolve(PUBLIC_IP, "1.2.3.4, 10.0.0.1, 172.16.0.9", "5.6.7.8", true),
                "多段 XFF 取最靠近客户端的第一跳");
        assertEquals("5.6.7.8",
                ClientIpResolver.resolve(PUBLIC_IP, "not-an-ip", "5.6.7.8", true),
                "XFF 脏值不能顶掉合法的 X-Real-IP");
        assertEquals(PUBLIC_IP,
                ClientIpResolver.resolve(PUBLIC_IP, null, null, true),
                "两个头都缺时回到不可伪造的 remoteAddr");
    }

    @Test
    @DisplayName("内网/保留地址判定：回环、私网、链路本地、唯一本地判为本地；公网文档段判为公网")
    void classifiesLocalAndPrivate() {
        assertTrue(ClientIpResolver.isLocalOrPrivate("127.0.0.1"));
        assertTrue(ClientIpResolver.isLocalOrPrivate("127.0.0.1:8080"), "带端口也要能判");
        assertTrue(ClientIpResolver.isLocalOrPrivate("10.1.2.3"));
        assertTrue(ClientIpResolver.isLocalOrPrivate("172.16.5.5"));
        assertTrue(ClientIpResolver.isLocalOrPrivate("192.168.1.5"));
        assertTrue(ClientIpResolver.isLocalOrPrivate("169.254.1.1"));
        assertTrue(ClientIpResolver.isLocalOrPrivate("fc00::1"), "IPv6 唯一本地地址 fc00::/7");
        assertTrue(ClientIpResolver.isLocalOrPrivate("0.0.0.0"));

        assertFalse(ClientIpResolver.isLocalOrPrivate(PUBLIC_IP), "公网地址应被判为可封禁对象");
        assertFalse(ClientIpResolver.isLocalOrPrivate("8.8.8.8"));
    }

    @Test
    @DisplayName("resolve 对空 remoteAddr 返回 unknown 而不是抛异常（非 HTTP 线程也要安全）")
    void resolveNeverThrows() {
        assertEquals(ClientIpResolver.UNKNOWN, ClientIpResolver.resolve(null, null, null, false));
        assertEquals(ClientIpResolver.UNKNOWN, ClientIpResolver.resolve("", null, null, false));
    }
}
