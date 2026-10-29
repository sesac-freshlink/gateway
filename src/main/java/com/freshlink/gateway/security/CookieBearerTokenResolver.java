package com.freshlink.gateway.security;

import com.freshlink.gateway.auth.AuthCookies;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.util.StringUtils;

/**
 * 검증할 ID 토큰을 찾는다. {@code Authorization: Bearer} 헤더가 있으면 그것, 없으면 {@code fl_id} 쿠키.
 * 헤더는 PowerShell 시험용으로 남긴다(aud가 bff인 토큰만 통과한다).
 * <p>
 * 로그인·갱신·로그아웃 같은 /api/auth 공개 경로에서는 쿠키를 읽지 않는다. 만료된 fl_id가 남아 있어도
 * 갱신·로그아웃이 401로 막히지 않게 하려는 것이다. /api/auth/me는 로그인이 필요하므로 쿠키를 읽는다.
 */
final class CookieBearerTokenResolver implements BearerTokenResolver {

    private final DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        String token = header.resolve(request);
        if (token != null) {
            return token;
        }
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/auth/") && !uri.equals("/api/auth/me")) {
            return null;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AuthCookies.ID_TOKEN.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
