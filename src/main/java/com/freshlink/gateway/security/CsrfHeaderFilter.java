package com.freshlink.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 쿠키 로그인의 CSRF 막기. /api/** 의 POST·PUT·PATCH·DELETE는 {@code X-Requested-With: freshlink}가 없으면 403이다.
 * <p>
 * 다른 사이트의 폼·이미지 요청은 이 헤더를 붙일 수 없다. 붙이려면 CORS 사전 요청이 필요한데 gateway는 CORS를 열지 않는다.
 * GET(구글 로그인 이동·콜백 포함)은 상태를 바꾸지 않으므로 보지 않는다.
 */
final class CsrfHeaderFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Requested-With";
    static final String VALUE = "freshlink";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE_METHODS.contains(request.getMethod()) || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!VALUE.equals(request.getHeader(HEADER))) {
            ErrorResponseWriter.write(response, 403, "CSRF_HEADER_REQUIRED", "요청 헤더가 올바르지 않습니다");
            return;
        }
        chain.doFilter(request, response);
    }
}
