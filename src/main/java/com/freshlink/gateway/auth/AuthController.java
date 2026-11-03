package com.freshlink.gateway.auth;

import com.freshlink.gateway.security.CognitoProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * BFF 로그인 API (README 'BFF 로그인'). 라우트로 넘기지 않고 gateway가 직접 처리한다.
 * <p>
 * 토큰은 HttpOnly 쿠키로만 주고 응답 본문·로그에는 넣지 않는다. 쿠키는 {@link AuthCookies}.
 * Cognito가 준 ID 토큰도 토큰 검증 규칙(iss·token_use·aud)을 통과해야 쿠키로 굽는다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_BASE64 = Base64.getUrlEncoder().withoutPadding();

    private final CognitoAuthService cognito;
    private final JwtDecoder jwtDecoder;
    private final CognitoProperties props;
    private final String frontendUrl;

    public AuthController(CognitoAuthService cognito, JwtDecoder jwtDecoder, CognitoProperties props,
                          @Value("${freshlink.frontend-url:}") String frontendUrl) {
        this.cognito = cognito;
        this.jwtDecoder = jwtDecoder;
        this.props = props;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record ConfirmRequest(@NotBlank String email, @NotBlank String code) {
    }

    /** 이메일·비밀번호 로그인 → 200 사용자 정보 + 쿠키 3개 */
    @PostMapping("/login")
    public ResponseEntity<AuthUser> login(@Valid @RequestBody LoginRequest req) {
        CognitoAuthService.Tokens tokens = cognito.login(req.email().trim(), req.password());
        Session session = session(tokens);
        HttpHeaders headers = new HttpHeaders();
        addSessionCookies(headers, tokens, session.username());
        return ResponseEntity.ok().headers(headers).body(session.user());
    }

    /** 가입 → 201. 이메일로 인증 코드가 간다 */
    @PostMapping("/signup")
    public ResponseEntity<Void> signUp(@Valid @RequestBody LoginRequest req) {
        cognito.signUp(req.email().trim(), req.password());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /** 인증 코드 확인 → 204 */
    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@Valid @RequestBody ConfirmRequest req) {
        cognito.confirmSignUp(req.email().trim(), req.code().trim());
        return ResponseEntity.noContent().build();
    }

    /** ID 토큰 갱신 → 200 사용자 정보 + 새 fl_id. 실패하면 401 + 쿠키 3개 삭제 */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        Optional<String> refreshToken = AuthCookies.read(request, AuthCookies.REFRESH_TOKEN);
        Optional<String> username = AuthCookies.read(request, AuthCookies.USERNAME);
        if (refreshToken.isEmpty() || username.isEmpty()) {
            return error(new AuthException(401, "SESSION_EXPIRED", "다시 로그인해 주세요"), AuthCookies.clearSession());
        }
        try {
            CognitoAuthService.Tokens tokens = cognito.refresh(refreshToken.get(), username.get());
            Session session = session(tokens);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.SET_COOKIE, AuthCookies.idToken(tokens.idToken(), tokens.expiresIn()).toString());
            if (tokens.refreshToken() != null) {
                // 리프레시 토큰 교체(rotation)를 켜면 새로 온다
                headers.add(HttpHeaders.SET_COOKIE,
                        AuthCookies.refreshToken(tokens.refreshToken(), refreshValidity()).toString());
            }
            return ResponseEntity.ok().headers(headers).body(session.user());
        } catch (AuthException e) {
            if (e.status() == 401) {
                return error(e, AuthCookies.clearSession());
            }
            throw e;
        }
    }

    /** 로그아웃 → 204 + 쿠키 3개 삭제. Cognito 토큰 취소가 실패해도 쿠키는 지운다 */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        AuthCookies.read(request, AuthCookies.REFRESH_TOKEN).ifPresent(cognito::revoke);
        return ResponseEntity.noContent().headers(AuthCookies.clearSession()).build();
    }

    /**
     * 로그아웃 뒤 Cognito 로그인 도메인의 로그인 기록까지 지운다 → Cognito /logout으로 302.
     * 이 기록이 남아 있으면 다음 구글 로그인이 계정 선택 없이 직전 계정으로 바로 된다 (BFF 인수인계 6장 7).
     * 화면은 POST /logout으로 쿠키를 지운 뒤 이 주소로 이동한다. Cognito는 {FRONTEND_URL}/login으로 돌려보낸다
     */
    @GetMapping("/logout/sso")
    public ResponseEntity<Void> ssoLogout() {
        URI logout = UriComponentsBuilder.fromUriString(props.domainUrl().replaceAll("/+$", "") + "/logout")
                .queryParam("client_id", props.bffClientId())
                .queryParam("logout_uri", frontendUrl + "/login")
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();
        return ResponseEntity.status(HttpStatus.FOUND).location(logout).build();
    }

    /** 새로 고침 뒤 로그인 복원. fl_id가 없거나 만료면 보안 설정이 먼저 401을 준다 */
    @GetMapping("/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal Jwt jwt) {
        AuthUser user = AuthUser.from(jwt);
        if (user == null) {
            return error(new AuthException(403, "NO_ROLE", "계정에 역할이 지정되지 않았습니다"), new HttpHeaders());
        }
        return ResponseEntity.ok(user);
    }

    /** 구글 로그인 시작: state·PKCE를 fl_oauth에 두고 Cognito 로그인 도메인으로 302 (Cognito 화면 없이 바로 구글) */
    @GetMapping("/google")
    public ResponseEntity<Void> google() {
        String state = random(32);
        String verifier = random(48);
        URI authorize = UriComponentsBuilder.fromUriString(props.domainUrl().replaceAll("/+$", "") + "/oauth2/authorize")
                .queryParam("identity_provider", "Google")
                .queryParam("client_id", props.bffClientId())
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("redirect_uri", callbackUrl())
                .queryParam("state", state)
                .queryParam("code_challenge", challenge(verifier))
                .queryParam("code_challenge_method", "S256")
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, AuthCookies.oauth(state + "." + verifier).toString())
                .location(authorize)
                .build();
    }

    /**
     * 구글 로그인 뒤 Cognito가 보내는 곳. state가 fl_oauth와 다르면 400.
     * 코드를 토큰으로 바꿔 쿠키 3개를 굽고 화면 첫 주소로 302. 구글에서 취소했거나 교환이 실패하면 로그인 화면으로 302
     */
    @GetMapping("/callback")
    public ResponseEntity<?> callback(HttpServletRequest request,
                                      @RequestParam(required = false) String code,
                                      @RequestParam(required = false) String state,
                                      @RequestParam(required = false) String error) {
        Optional<String> saved = AuthCookies.read(request, AuthCookies.OAUTH);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, AuthCookies.clearOauth().toString());

        String[] parts = saved.map(v -> v.split("\\.", 2)).orElse(new String[0]);
        if (parts.length != 2 || state == null || !constantTimeEquals(parts[0], state)) {
            return error(new AuthException(400, "INVALID_OAUTH_STATE", "로그인 요청이 올바르지 않습니다. 다시 시도해 주세요"), headers);
        }
        if (error != null || code == null) {
            log.info("구글 로그인 취소 또는 실패 error={}", error);
            return redirect(headers, frontendUrl + "/login?error=google");
        }
        CognitoAuthService.Tokens tokens = cognito.exchangeCode(code, parts[1], callbackUrl());
        if (tokens == null) {
            return redirect(headers, frontendUrl + "/login?error=google");
        }
        Session session;
        try {
            session = session(tokens);
        } catch (AuthException e) {
            log.warn("구글 로그인 토큰 확인 실패 code={}", e.code());
            return redirect(headers, frontendUrl + "/login?error=google");
        }
        addSessionCookies(headers, tokens, session.username());
        return redirect(headers, frontendUrl + "/");
    }

    // ------------------------------------------------------------------ 오류

    @ExceptionHandler(AuthException.class)
    ResponseEntity<Map<String, Object>> handle(AuthException e) {
        return error(e, new HttpHeaders());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, Object>> invalid(Exception e) {
        return error(new AuthException(400, "INVALID_REQUEST", "입력 값을 확인해 주세요"), new HttpHeaders());
    }

    private static ResponseEntity<Map<String, Object>> error(AuthException e, HttpHeaders headers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", e.getMessage());
        body.put("traceId", MDC.get("traceId"));
        return ResponseEntity.status(e.status()).headers(headers).body(body);
    }

    // ------------------------------------------------------------------ 도우미

    /** 쿠키로 구울 수 있는 ID 토큰인지 확인하고 사용자 정보와 cognito:username을 꺼낸다 */
    private Session session(CognitoAuthService.Tokens tokens) {
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(tokens.idToken());
        } catch (JwtException e) {
            log.error("Cognito가 준 ID 토큰이 검증 규칙을 통과하지 못했다 (COGNITO_CLIENT_ID가 bff인지 확인) error={}",
                    e.getClass().getSimpleName());
            throw new AuthException(502, "AUTH_UNAVAILABLE", "로그인 서버에 연결하지 못했습니다. 잠시 뒤 다시 시도해 주세요");
        }
        AuthUser user = AuthUser.from(jwt);
        if (user == null) {
            throw new AuthException(403, "NO_ROLE", "계정에 역할이 지정되지 않았습니다");
        }
        String username = jwt.getClaimAsString("cognito:username");
        if (username == null) {
            throw new AuthException(502, "AUTH_UNAVAILABLE", "로그인 서버에 연결하지 못했습니다. 잠시 뒤 다시 시도해 주세요");
        }
        return new Session(user, username);
    }

    private record Session(AuthUser user, String username) {
    }

    private void addSessionCookies(HttpHeaders headers, CognitoAuthService.Tokens tokens, String username) {
        headers.add(HttpHeaders.SET_COOKIE, AuthCookies.idToken(tokens.idToken(), tokens.expiresIn()).toString());
        if (tokens.refreshToken() != null) {
            headers.add(HttpHeaders.SET_COOKIE, AuthCookies.refreshToken(tokens.refreshToken(), refreshValidity()).toString());
            headers.add(HttpHeaders.SET_COOKIE, AuthCookies.username(username, refreshValidity()).toString());
        }
    }

    private Duration refreshValidity() {
        return props.refreshTokenValidity() == null ? Duration.ofDays(1) : props.refreshTokenValidity();
    }

    private String callbackUrl() {
        return frontendUrl + "/api/auth/callback";
    }

    private static ResponseEntity<Void> redirect(HttpHeaders headers, String location) {
        return ResponseEntity.status(HttpStatus.FOUND).headers(headers).location(URI.create(location)).build();
    }

    private static String random(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return URL_BASE64.encodeToString(buf);
    }

    /** PKCE S256: Base64URL(SHA-256(code_verifier)) */
    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return URL_BASE64.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
