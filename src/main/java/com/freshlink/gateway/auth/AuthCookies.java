package com.freshlink.gateway.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * BFF 로그인 쿠키 (README 'BFF 로그인' 쿠키 표). 모두 HttpOnly·Secure이고 Domain은 붙이지 않는다.
 * <ul>
 *   <li>{@code fl_id}: ID 토큰. Path /api, SameSite=Strict, ID 토큰 만료와 같다</li>
 *   <li>{@code fl_refresh}: 리프레시 토큰. Path /api/auth, SameSite=Strict, 리프레시 기간</li>
 *   <li>{@code fl_uid}: cognito:username. 갱신 SECRET_HASH용. Path /api/auth, SameSite=Strict, 리프레시 기간</li>
 *   <li>{@code fl_oauth}: 구글 로그인 state·PKCE code_verifier. Path /api/auth, SameSite=Lax(Cognito에서 돌아오는 이동에도 붙게), 10분</li>
 * </ul>
 * 브라우저는 localhost를 안전한 곳으로 봐서 PC(Vite 5173 → gateway 8080)에서도 Secure 쿠키가 붙는다.
 */
public final class AuthCookies {

    public static final String ID_TOKEN = "fl_id";
    public static final String REFRESH_TOKEN = "fl_refresh";
    public static final String USERNAME = "fl_uid";
    public static final String OAUTH = "fl_oauth";

    static final String API_PATH = "/api";
    static final String AUTH_PATH = "/api/auth";
    static final Duration OAUTH_MAX_AGE = Duration.ofMinutes(10);

    private AuthCookies() {
    }

    static ResponseCookie idToken(String value, Duration maxAge) {
        return strict(ID_TOKEN, value, API_PATH, maxAge);
    }

    static ResponseCookie refreshToken(String value, Duration maxAge) {
        return strict(REFRESH_TOKEN, value, AUTH_PATH, maxAge);
    }

    static ResponseCookie username(String value, Duration maxAge) {
        return strict(USERNAME, value, AUTH_PATH, maxAge);
    }

    static ResponseCookie oauth(String value) {
        return base(OAUTH, value, AUTH_PATH, OAUTH_MAX_AGE).sameSite("Lax").build();
    }

    /** 로그인 쿠키 3개를 지우는 Set-Cookie 값 (Max-Age=0) */
    static HttpHeaders clearSession() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, idToken("", Duration.ZERO).toString());
        headers.add(HttpHeaders.SET_COOKIE, refreshToken("", Duration.ZERO).toString());
        headers.add(HttpHeaders.SET_COOKIE, username("", Duration.ZERO).toString());
        return headers;
    }

    static ResponseCookie clearOauth() {
        return base(OAUTH, "", AUTH_PATH, Duration.ZERO).sameSite("Lax").build();
    }

    static Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isEmpty()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private static ResponseCookie strict(String name, String value, String path, Duration maxAge) {
        return base(name, value, path, maxAge).sameSite("Strict").build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(true).path(path).maxAge(maxAge);
    }
}
