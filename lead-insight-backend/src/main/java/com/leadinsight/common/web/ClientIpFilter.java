package com.leadinsight.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import java.io.IOException;

/**
 * 把请求来源 IP 放进 {@link ClientIpContext}，供成本红线按 IP 封禁使用。
 *
 * <p>放在过滤器链最前：业务侧（含异步抽取任务的参数捕获）都要能读到它。
 * 请求结束务必清除，否则线程池复用会把上一个请求的 IP 带到下一个请求。</p>
 *
 * <p><b>取址方式：</b>先用 Spring 自带的 {@link WebUtils#getNativeRequest} 把包装过的
 * request（{@code ContentCachingRequestWrapper}、安全框架的 {@code FirewalledRequest} 等）
 * 剥回容器原生 {@link HttpServletRequest}，再读容器填好的 {@code remoteAddr / remotePort}。
 * 走原生对象而不是手写正则去抠头，是因为只有这两个值由 TCP 连接本身决定、客户端无法伪造。</p>
 *
 * <p><b>为什么不用 {@code UrlPathHelper}：</b>它是路径匹配工具，提供的
 * {@code getOriginatingRequestUri(HttpServletRequest)} 返回的是请求 <b>URI（路径）</b>字符串，
 * 不含客户端地址；取 IP 用不到它。Spring 侧能借力的只有上面的「解包到原生 request」。</p>
 *
 * <p>{@code app.security.trust-forwarded-for} 默认 false——见 {@link ClientIpResolver} 的说明：
 * 采信可伪造的代理头等于把 IP 封禁交给攻击者决定。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ClientIpFilter extends OncePerRequestFilter {

    private final boolean trustForwardedFor;

    public ClientIpFilter(@Value("${app.security.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        HttpServletRequest nativeRequest = WebUtils.getNativeRequest(request, HttpServletRequest.class);
        if (nativeRequest == null) {
            nativeRequest = request;
        }

        String clientIp = ClientIpResolver.resolve(
                nativeRequest.getRemoteAddr(),
                nativeRequest.getHeader("X-Forwarded-For"),
                nativeRequest.getHeader("X-Real-IP"),
                trustForwardedFor);

        if (log.isDebugEnabled()) {
            ClientIpResolver.Endpoint endpoint = ClientIpResolver.parse(nativeRequest.getRemoteAddr());
            log.debug("来源地址解析：remote={}（端口 {}）→ 采用 {}",
                    endpoint == null ? nativeRequest.getRemoteAddr() : endpoint.authority(),
                    nativeRequest.getRemotePort(), clientIp);
        }

        ClientIpContext.set(clientIp);
        try {
            filterChain.doFilter(request, response);
        } finally {
            ClientIpContext.clear();
        }
    }
}
