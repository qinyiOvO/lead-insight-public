package com.leadinsight.security;

import com.leadinsight.common.result.ResultCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Bearer Token 解析过滤器。解析失败时不在本类直接写响应，
 * 而是把失败原因放进请求属性，交由 {@link RestAuthenticationEntryPoint} 统一输出 JSON。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 请求属性名：本次请求的认证失败原因 */
    public static final String ATTR_AUTH_ERROR = "com.leadinsight.authError";

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final JwtUserDetailsService jwtUserDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtUtil.parse(token);
            Long userId = jwtUtil.extractUserId(claims);
            String username = jwtUtil.extractUsername(claims);
            if (userId != null && username != null
                    && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = jwtUserDetailsService.loadUserByUsername(username);
                if (userDetails.isEnabled()) {
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    request.setAttribute(ATTR_AUTH_ERROR, ResultCode.UNAUTHORIZED);
                }
            }
        } catch (ExpiredJwtException e) {
            log.debug("令牌已过期: {}", e.getMessage());
            request.setAttribute(ATTR_AUTH_ERROR, ResultCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("令牌无效: {}", e.getMessage());
            request.setAttribute(ATTR_AUTH_ERROR, ResultCode.UNAUTHORIZED);
        } catch (AuthenticationException e) {
            // 令牌本身合法，但用户已不存在/被禁用（loadUserByUsername 抛 UsernameNotFoundException 等）。
            // 不设 SecurityContext，交由 RestAuthenticationEntryPoint 统一返 401，避免外溢成 500。
            log.debug("令牌有效但用户不可用: {}", e.getMessage());
            SecurityContextHolder.clearContext();
            request.setAttribute(ATTR_AUTH_ERROR, ResultCode.UNAUTHORIZED);
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (StringUtils.hasText(header) && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
