package com.freshlink.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ChangePasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CodeMismatchException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ConfirmSignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GlobalSignOutRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GlobalSignOutResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InternalErrorException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.LimitExceededException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RevokeTokenRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotConfirmedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * BFF 로그인 API(/api/auth/*)를 실제 포트로 확인한다.
 * Cognito SDK 클라이언트는 목으로, 공개 키(JWKS)와 로그인 도메인 /oauth2/token은 가짜 서버로 바꾼다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthControllerTests {

    static final String BFF_CLIENT_ID = "bff-client-id";
    static final String CLIENT_SECRET = "test-client-secret";
    static final String POOL_ID = "ap-northeast-2_TEST";
    static final String FRONTEND = "https://front.example";
    static final RSAKey KEY = newKey("cognito-key");

    /** /oauth2/token이 받은 form과 Authorization 헤더 */
    static final AtomicReference<Map<String, String>> TOKEN_FORM = new AtomicReference<>();
    static final AtomicReference<String> TOKEN_AUTH = new AtomicReference<>();
    /** /oauth2/token이 돌려줄 상태 코드와 ID 토큰 */
    static final AtomicReference<Integer> TOKEN_STATUS = new AtomicReference<>(200);
    static final AtomicReference<String> TOKEN_ID = new AtomicReference<>();

    static final HttpServer COGNITO = start();
    static final String ISSUER = "http://localhost:" + COGNITO.getAddress().getPort() + "/" + POOL_ID;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("freshlink.cognito.issuer-uri", () -> ISSUER);
        registry.add("freshlink.cognito.client-id", () -> BFF_CLIENT_ID);
        registry.add("freshlink.cognito.bff-client-id", () -> BFF_CLIENT_ID);
        registry.add("freshlink.cognito.client-secret", () -> CLIENT_SECRET);
        registry.add("freshlink.cognito.user-pool-id", () -> POOL_ID);
        registry.add("freshlink.cognito.domain-url", () -> "http://localhost:" + COGNITO.getAddress().getPort() + "/");
        registry.add("freshlink.frontend-url", () -> FRONTEND + "/");
    }

    @AfterAll
    static void stop() {
        COGNITO.stop(0);
    }

    @MockitoBean
    CognitoIdentityProviderClient cognito;

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void clear() {
        reset(cognito);
        TOKEN_FORM.set(null);
        TOKEN_AUTH.set(null);
        TOKEN_STATUS.set(200);
        TOKEN_ID.set(null);
    }

    // ------------------------------------------------------------------ 로그인

    @Test
    @DisplayName("로그인 성공: 200 사용자 정보 + 쿠키 3개, 본문에 토큰 없음, SECRET_HASH는 이메일 기준")
    void login_success() throws Exception {
        String sub = UUID.randomUUID().toString();
        String idToken = idToken(c -> c.subject(sub).claim("cognito:username", sub)
                .claim("cognito:groups", List.of("OWNER")).claim("custom:storeId", "2").claim("email", "owner2@freshlink.test"));
        when(cognito.adminInitiateAuth(any(AdminInitiateAuthRequest.class))).thenReturn(authResult(idToken, "refresh-1"));

        HttpResponse<String> res = post("/api/auth/login", "{\"email\":\" owner2@freshlink.test \",\"password\":\"pw\"}", null);

        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(res.body());
        assertThat(body.get("userId").asText()).isEqualTo(sub);
        assertThat(body.get("role").asText()).isEqualTo("OWNER");
        assertThat(body.get("storeId").asText()).isEqualTo("2");
        assertThat(body.get("email").asText()).isEqualTo("owner2@freshlink.test");
        assertThat(res.body()).doesNotContain(idToken).doesNotContain("refresh-1");

        Map<String, String> cookies = setCookies(res);
        assertThat(cookies.get("fl_id")).startsWith("fl_id=" + idToken + ";")
                .contains("Path=/api;").contains("Max-Age=3600").contains("Secure").contains("HttpOnly")
                .contains("SameSite=Strict").doesNotContain("Domain");
        assertThat(cookies.get("fl_refresh")).startsWith("fl_refresh=refresh-1;")
                .contains("Path=/api/auth;").contains("Max-Age=86400").contains("HttpOnly").contains("SameSite=Strict");
        assertThat(cookies.get("fl_uid")).startsWith("fl_uid=" + sub + ";").contains("Path=/api/auth;");

        ArgumentCaptor<AdminInitiateAuthRequest> req = ArgumentCaptor.forClass(AdminInitiateAuthRequest.class);
        verify(cognito).adminInitiateAuth(req.capture());
        assertThat(req.getValue().authFlow()).isEqualTo(AuthFlowType.ADMIN_USER_PASSWORD_AUTH);
        assertThat(req.getValue().userPoolId()).isEqualTo(POOL_ID);
        assertThat(req.getValue().clientId()).isEqualTo(BFF_CLIENT_ID);
        assertThat(req.getValue().authParameters())
                .containsEntry("USERNAME", "owner2@freshlink.test")
                .containsEntry("SECRET_HASH", secretHash("owner2@freshlink.test"));
    }

    @Test
    @DisplayName("비밀번호가 틀리면 401 INVALID_CREDENTIALS, 쿠키 없음")
    void login_wrongPassword_401() throws Exception {
        when(cognito.adminInitiateAuth(any(AdminInitiateAuthRequest.class)))
                .thenThrow(NotAuthorizedException.builder().message("Incorrect username or password.").build());
        HttpResponse<String> res = post("/api/auth/login", "{\"email\":\"a@b.c\",\"password\":\"x\"}", null);
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(json.readTree(res.body()).get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(res.body()).doesNotContain("Incorrect");
        assertThat(res.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    @DisplayName("이메일 미인증이면 403 USER_NOT_CONFIRMED")
    void login_unconfirmed_403() throws Exception {
        when(cognito.adminInitiateAuth(any(AdminInitiateAuthRequest.class)))
                .thenThrow(UserNotConfirmedException.builder().message("x").build());
        HttpResponse<String> res = post("/api/auth/login", "{\"email\":\"a@b.c\",\"password\":\"x\"}", null);
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(json.readTree(res.body()).get("code").asText()).isEqualTo("USER_NOT_CONFIRMED");
    }

    @Test
    @DisplayName("역할 그룹이 없는 계정은 403 NO_ROLE, 쿠키 없음")
    void login_noRole_403() throws Exception {
        String idToken = idToken(c -> c.claim("cognito:groups", List.of(POOL_ID + "_Google")));
        when(cognito.adminInitiateAuth(any(AdminInitiateAuthRequest.class))).thenReturn(authResult(idToken, "r"));
        HttpResponse<String> res = post("/api/auth/login", "{\"email\":\"a@b.c\",\"password\":\"x\"}", null);
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(json.readTree(res.body()).get("code").asText()).isEqualTo("NO_ROLE");
        assertThat(res.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    @DisplayName("이메일·비밀번호가 비면 400, Cognito를 부르지 않는다")
    void login_blank_400() throws Exception {
        HttpResponse<String> res = post("/api/auth/login", "{\"email\":\"\",\"password\":\"x\"}", null);
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(json.readTree(res.body()).get("code").asText()).isEqualTo("INVALID_REQUEST");
        verify(cognito, never()).adminInitiateAuth(any(AdminInitiateAuthRequest.class));
    }

    @Test
    @DisplayName("X-Requested-With가 없는 로그인 POST는 403")
    void login_withoutCsrfHeader_403() throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"a@b.c\",\"password\":\"x\"}"))
                .header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(403);
        verify(cognito, never()).adminInitiateAuth(any(AdminInitiateAuthRequest.class));
    }

    // ------------------------------------------------------------------ 가입·인증 코드

    @Test
    @DisplayName("가입: 201, 속성은 email만, SECRET_HASH 붙임. 이미 있으면 409")
    void signUp() throws Exception {
        HttpResponse<String> ok = post("/api/auth/signup", "{\"email\":\"new@freshlink.test\",\"password\":\"Passw0rd!\"}", null);
        assertThat(ok.statusCode()).isEqualTo(201);
        ArgumentCaptor<SignUpRequest> req = ArgumentCaptor.forClass(SignUpRequest.class);
        verify(cognito).signUp(req.capture());
        assertThat(req.getValue().username()).isEqualTo("new@freshlink.test");
        assertThat(req.getValue().secretHash()).isEqualTo(secretHash("new@freshlink.test"));
        assertThat(req.getValue().userAttributes()).hasSize(1);
        assertThat(req.getValue().userAttributes().get(0).name()).isEqualTo("email");

        doThrow(UsernameExistsException.builder().message("x").build()).when(cognito).signUp(any(SignUpRequest.class));
        HttpResponse<String> dup = post("/api/auth/signup", "{\"email\":\"new@freshlink.test\",\"password\":\"Passw0rd!\"}", null);
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(json.readTree(dup.body()).get("code").asText()).isEqualTo("EMAIL_ALREADY_EXISTS");
    }

    @Test
    @DisplayName("인증 코드: 맞으면 204, 틀리면 400 CODE_MISMATCH")
    void confirm() throws Exception {
        assertThat(post("/api/auth/confirm", "{\"email\":\"new@freshlink.test\",\"code\":\" 123456 \"}", null).statusCode())
                .isEqualTo(204);
        ArgumentCaptor<ConfirmSignUpRequest> req = ArgumentCaptor.forClass(ConfirmSignUpRequest.class);
        verify(cognito).confirmSignUp(req.capture());
        assertThat(req.getValue().confirmationCode()).isEqualTo("123456");
        assertThat(req.getValue().secretHash()).isEqualTo(secretHash("new@freshlink.test"));

        doThrow(CodeMismatchException.builder().message("x").build()).when(cognito).confirmSignUp(any(ConfirmSignUpRequest.class));
        HttpResponse<String> bad = post("/api/auth/confirm", "{\"email\":\"new@freshlink.test\",\"code\":\"000000\"}", null);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json.readTree(bad.body()).get("code").asText()).isEqualTo("CODE_MISMATCH");
    }

    // ------------------------------------------------------------------ 갱신·로그아웃·me

    @Test
    @DisplayName("갱신 성공: 새 fl_id, SECRET_HASH는 fl_uid(cognito:username) 기준. 만료된 fl_id가 남아 있어도 된다")
    void refresh_success() throws Exception {
        String username = UUID.randomUUID().toString();
        String fresh = idToken(c -> c.claim("cognito:username", username).claim("cognito:groups", List.of("CUSTOMER")));
        when(cognito.adminInitiateAuth(any(AdminInitiateAuthRequest.class))).thenReturn(authResult(fresh, null));
        String expired = idToken(c -> c.expirationTime(Date.from(Instant.now().minusSeconds(3600))));

        HttpResponse<String> res = post("/api/auth/refresh", null,
                "fl_id=" + expired + "; fl_refresh=refresh-1; fl_uid=" + username);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(json.readTree(res.body()).get("role").asText()).isEqualTo("CUSTOMER");
        Map<String, String> cookies = setCookies(res);
        assertThat(cookies.get("fl_id")).startsWith("fl_id=" + fresh + ";").contains("Max-Age=3600");
        assertThat(cookies).doesNotContainKey("fl_refresh");

        ArgumentCaptor<AdminInitiateAuthRequest> req = ArgumentCaptor.forClass(AdminInitiateAuthRequest.class);
        verify(cognito).adminInitiateAuth(req.capture());
        assertThat(req.getValue().authFlow()).isEqualTo(AuthFlowType.REFRESH_TOKEN_AUTH);
        assertThat(req.getValue().authParameters())
                .containsEntry("REFRESH_TOKEN", "refresh-1")
                .containsEntry("SECRET_HASH", secretHash(username));
    }

    @Test
    @DisplayName("갱신 실패면 401 + 쿠키 3개 삭제, 리프레시 쿠키가 없어도 401")
    void refresh_failure_401_clearsCookies() throws Exception {
        when(cognito.adminInitiateAuth(any(AdminInitiateAuthRequest.class)))
                .thenThrow(NotAuthorizedException.builder().message("Invalid Refresh Token").build());
        HttpResponse<String> res = post("/api/auth/refresh", null, "fl_refresh=old; fl_uid=u");
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(json.readTree(res.body()).get("code").asText()).isEqualTo("SESSION_EXPIRED");
        assertCleared(res);

        HttpResponse<String> none = post("/api/auth/refresh", null, null);
        assertThat(none.statusCode()).isEqualTo(401);
        assertCleared(none);
    }

    @Test
    @DisplayName("로그아웃: 리프레시 토큰을 취소하고 204 + 쿠키 3개 삭제. Cognito가 실패해도 쿠키는 지운다")
    void logout() throws Exception {
        HttpResponse<String> res = post("/api/auth/logout", null, "fl_refresh=refresh-1; fl_uid=u");
        assertThat(res.statusCode()).isEqualTo(204);
        assertCleared(res);
        ArgumentCaptor<RevokeTokenRequest> req = ArgumentCaptor.forClass(RevokeTokenRequest.class);
        verify(cognito).revokeToken(req.capture());
        assertThat(req.getValue().token()).isEqualTo("refresh-1");
        assertThat(req.getValue().clientSecret()).isEqualTo(CLIENT_SECRET);

        doThrow(NotAuthorizedException.builder().message("x").build()).when(cognito).revokeToken(any(RevokeTokenRequest.class));
        HttpResponse<String> failed = post("/api/auth/logout", null, "fl_refresh=refresh-1");
        assertThat(failed.statusCode()).isEqualTo(204);
        assertCleared(failed);
    }

    @Test
    @DisplayName("me: fl_id 쿠키로 200 사용자 정보, 쿠키가 없으면 401")
    void me() throws Exception {
        String sub = UUID.randomUUID().toString();
        String idToken = idToken(c -> c.subject(sub).claim("cognito:groups", List.of("CUSTOMER", POOL_ID + "_Google"))
                .claim("email", "g@gmail.com"));
        HttpResponse<String> res = get("/api/auth/me", "fl_id=" + idToken);
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(res.body());
        assertThat(body.get("userId").asText()).isEqualTo(sub);
        assertThat(body.get("role").asText()).isEqualTo("CUSTOMER");
        assertThat(body.get("storeId").isNull()).isTrue();
        assertThat(body.get("email").asText()).isEqualTo("g@gmail.com");
        assertThat(body.get("passwordLogin").asBoolean()).isFalse();

        assertThat(get("/api/auth/me", null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("me: 이메일 가입 계정은 passwordLogin true, identities가 있으면 false")
    void me_passwordLogin() throws Exception {
        String email = idToken(c -> c.claim("cognito:groups", List.of("OWNER")).claim("custom:storeId", "7"));
        assertThat(json.readTree(get("/api/auth/me", "fl_id=" + email).body()).get("passwordLogin").asBoolean()).isTrue();

        String federated = idToken(c -> c.claim("cognito:groups", List.of("CUSTOMER"))
                .claim("identities", List.of(Map.of("providerName", "Google"))));
        assertThat(json.readTree(get("/api/auth/me", "fl_id=" + federated).body()).get("passwordLogin").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ 비밀번호 변경 (A4)

    static final String PASSWORD_BODY = "{\"currentPassword\":\"Old-pass1\",\"newPassword\":\"New-pass1\"}";

    String sessionCookie(String username) throws Exception {
        String id = idToken(c -> c.claim("cognito:username", username).claim("cognito:groups", List.of("CUSTOMER")));
        return "fl_id=" + id + "; fl_refresh=refresh-1; fl_uid=" + username;
    }

    @Test
    @DisplayName("비밀번호 변경 성공: 리프레시로 받은 access token으로 ChangePassword → GlobalSignOut, 204, 로그인 쿠키 지움")
    void password_success() throws Exception {
        String username = UUID.randomUUID().toString();
        when(cognito.adminInitiateAuth(any(AdminInitiateAuthRequest.class))).thenReturn(authResult("new-id", null, "access-1"));

        HttpResponse<String> res = post("/api/auth/password", PASSWORD_BODY, sessionCookie(username));

        assertThat(res.statusCode()).isEqualTo(204);
        assertCleared(res);
        ArgumentCaptor<AdminInitiateAuthRequest> refresh = ArgumentCaptor.forClass(AdminInitiateAuthRequest.class);
        verify(cognito).adminInitiateAuth(refresh.capture());
        assertThat(refresh.getValue().authFlow()).isEqualTo(AuthFlowType.REFRESH_TOKEN_AUTH);
        assertThat(refresh.getValue().authParameters()).containsEntry("REFRESH_TOKEN", "refresh-1")
                .containsEntry("SECRET_HASH", secretHash(username));
        ArgumentCaptor<ChangePasswordRequest> change = ArgumentCaptor.forClass(ChangePasswordRequest.class);
        verify(cognito).changePassword(change.capture());
        assertThat(change.getValue().accessToken()).isEqualTo("access-1");
        assertThat(change.getValue().previousPassword()).isEqualTo("Old-pass1");
        assertThat(change.getValue().proposedPassword()).isEqualTo("New-pass1");
        ArgumentCaptor<GlobalSignOutRequest> signOut = ArgumentCaptor.forClass(GlobalSignOutRequest.class);
        verify(cognito).globalSignOut(signOut.capture());
        assertThat(signOut.getValue().accessToken()).isEqualTo("access-1");
    }

    @Test
    @DisplayName("비밀번호 변경: 모든 기기 로그아웃이 3번 다 실패하면 200 otherDevicesSignedOut=false, 쿠키는 지움(비밀번호는 바뀜)")
    void password_globalSignOutFailsThreeTimes_200Flag() throws Exception {
        when(cognito.adminInitiateAuth(any(AdminInitiateAuthRequest.class))).thenReturn(authResult("new-id", null, "access-1"));
        when(cognito.globalSignOut(any(GlobalSignOutRequest.class)))
                .thenThrow(InternalErrorException.builder().message("x").build());

        HttpResponse<String> res = post("/api/auth/password", PASSWORD_BODY, sessionCookie(UUID.randomUUID().toString()));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(json.readTree(res.body()).get("otherDevicesSignedOut").asBoolean(true)).isFalse();
        assertCleared(res);
        verify(cognito).changePassword(any(ChangePasswordRequest.class));
        verify(cognito, times(3)).globalSignOut(any(GlobalSignOutRequest.class));
    }

    @Test
    @DisplayName("비밀번호 변경: 모든 기기 로그아웃이 두 번 실패한 뒤 세 번째에 되면 204")
    void password_globalSignOutSucceedsOnRetry_204() throws Exception {
        when(cognito.adminInitiateAuth(any(AdminInitiateAuthRequest.class))).thenReturn(authResult("new-id", null, "access-1"));
        when(cognito.globalSignOut(any(GlobalSignOutRequest.class)))
                .thenThrow(InternalErrorException.builder().message("x").build())
                .thenThrow(InternalErrorException.builder().message("x").build())
                .thenReturn(GlobalSignOutResponse.builder().build());

        HttpResponse<String> res = post("/api/auth/password", PASSWORD_BODY, sessionCookie(UUID.randomUUID().toString()));

        assertThat(res.statusCode()).isEqualTo(204);
        assertCleared(res);
        verify(cognito, times(3)).globalSignOut(any(GlobalSignOutRequest.class));
    }

    @Test
    @DisplayName("비밀번호 변경: 리프레시 쿠키의 사용자(fl_uid)가 로그인한 사용자와 다르면 401, Cognito를 부르지 않는다")
    void password_otherUsersRefreshCookie_401() throws Exception {
        String id = idToken(c -> c.claim("cognito:username", "me").claim("cognito:groups", List.of("CUSTOMER")));
        HttpResponse<String> res = post("/api/auth/password", PASSWORD_BODY, "fl_id=" + id + "; fl_refresh=r; fl_uid=someone-else");

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(json.readTree(res.body()).get("code").asText()).isEqualTo("SESSION_EXPIRED");
        verify(cognito, never()).adminInitiateAuth(any(AdminInitiateAuthRequest.class));
        verify(cognito, never()).changePassword(any(ChangePasswordRequest.class));
    }

    @Test
    @DisplayName("비밀번호 변경: 256자를 넘는 비밀번호는 400 INVALID_REQUEST, Cognito를 부르지 않는다")
    void password_tooLong_400() throws Exception {
        String body = "{\"currentPassword\":\"Old-pass1\",\"newPassword\":\"" + "A1!a".repeat(65) + "\"}";
        HttpResponse<String> res = post("/api/auth/password", body, sessionCookie(UUID.randomUUID().toString()));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(json.readTree(res.body()).get("code").asText()).isEqualTo("INVALID_REQUEST");
        verify(cognito, never()).changePassword(any(ChangePasswordRequest.class));
    }

    @Test
    @DisplayName("비밀번호 변경: 관리자는 403 PASSWORD_CHANGE_NOT_ALLOWED, Cognito를 부르지 않는다")
    void password_admin_403() throws Exception {
        String id = idToken(c -> c.claim("cognito:groups", List.of("ADMIN")));
        HttpResponse<String> res = post("/api/auth/password", PASSWORD_BODY, "fl_id=" + id + "; fl_refresh=r; fl_uid=admin");
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(json.readTree(res.body()).get("code").asText()).isEqualTo("PASSWORD_CHANGE_NOT_ALLOWED");
        assertThat(res.headers().allValues("Set-Cookie")).isEmpty();
        verify(cognito, never()).adminInitiateAuth(any(AdminInitiateAuthRequest.class));
        verify(cognito, never()).changePassword(any(ChangePasswordRequest.class));
    }

    @Test
    @DisplayName("비밀번호 변경 실패: 현재 비밀번호 틀림 400, 규칙 위반 400, 너무 많음 429. 쿠키는 그대로, 로그아웃 안 함")
    void password_cognitoErrors() throws Exception {
        String cookie = sessionCookie(UUID.randomUUID().toString());
        when(cognito.adminInitiateAuth(any(AdminInitiateAuthRequest.class))).thenReturn(authResult("new-id", null, "access-1"));

        doThrow(NotAuthorizedException.builder().message("x").build()).when(cognito).changePassword(any(ChangePasswordRequest.class));
        HttpResponse<String> wrong = post("/api/auth/password", PASSWORD_BODY, cookie);
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(json.readTree(wrong.body()).get("code").asText()).isEqualTo("WRONG_CURRENT_PASSWORD");
        assertThat(wrong.headers().allValues("Set-Cookie")).isEmpty();

        doThrow(InvalidPasswordException.builder().message("x").build()).when(cognito).changePassword(any(ChangePasswordRequest.class));
        HttpResponse<String> invalid = post("/api/auth/password", PASSWORD_BODY, cookie);
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(json.readTree(invalid.body()).get("code").asText()).isEqualTo("INVALID_PASSWORD");

        doThrow(LimitExceededException.builder().message("x").build()).when(cognito).changePassword(any(ChangePasswordRequest.class));
        HttpResponse<String> limit = post("/api/auth/password", PASSWORD_BODY, cookie);
        assertThat(limit.statusCode()).isEqualTo(429);
        assertThat(json.readTree(limit.body()).get("code").asText()).isEqualTo("TOO_MANY_ATTEMPTS");
        verify(cognito, never()).globalSignOut(any(GlobalSignOutRequest.class));
    }

    @Test
    @DisplayName("비밀번호 변경: 리프레시가 만료면 401 SESSION_EXPIRED + 쿠키 지움, 리프레시 쿠키가 없어도 401")
    void password_sessionExpired_401() throws Exception {
        when(cognito.adminInitiateAuth(any(AdminInitiateAuthRequest.class)))
                .thenThrow(NotAuthorizedException.builder().message("x").build());
        HttpResponse<String> res = post("/api/auth/password", PASSWORD_BODY, sessionCookie(UUID.randomUUID().toString()));
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(json.readTree(res.body()).get("code").asText()).isEqualTo("SESSION_EXPIRED");
        assertCleared(res);
        verify(cognito, never()).changePassword(any(ChangePasswordRequest.class));

        String id = idToken(c -> c.claim("cognito:groups", List.of("CUSTOMER")));
        HttpResponse<String> noRefresh = post("/api/auth/password", PASSWORD_BODY, "fl_id=" + id);
        assertThat(noRefresh.statusCode()).isEqualTo(401);
        assertCleared(noRefresh);
    }

    @Test
    @DisplayName("비밀번호 변경: 구글 가입 계정은 403 PASSWORD_LOGIN_ONLY, Cognito를 부르지 않는다")
    void password_google_403() throws Exception {
        String id = idToken(c -> c.claim("cognito:groups", List.of("CUSTOMER", POOL_ID + "_Google")));
        HttpResponse<String> res = post("/api/auth/password", PASSWORD_BODY, "fl_id=" + id + "; fl_refresh=r; fl_uid=google_1");
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(json.readTree(res.body()).get("code").asText()).isEqualTo("PASSWORD_LOGIN_ONLY");
        verify(cognito, never()).adminInitiateAuth(any(AdminInitiateAuthRequest.class));
        verify(cognito, never()).changePassword(any(ChangePasswordRequest.class));
    }

    @Test
    @DisplayName("비밀번호 변경: 로그인 안 하면 401, 빈 값이면 400, X-Requested-With가 없으면 403")
    void password_guards() throws Exception {
        assertThat(post("/api/auth/password", PASSWORD_BODY, null).statusCode()).isEqualTo(401);

        String cookie = sessionCookie(UUID.randomUUID().toString());
        HttpResponse<String> blank = post("/api/auth/password", "{\"currentPassword\":\"\",\"newPassword\":\"x\"}", cookie);
        assertThat(blank.statusCode()).isEqualTo(400);

        HttpResponse<String> noCsrf = http.send(HttpRequest.newBuilder(uri("/api/auth/password"))
                .POST(HttpRequest.BodyPublishers.ofString(PASSWORD_BODY))
                .header("Content-Type", "application/json").header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(noCsrf.statusCode()).isEqualTo(403);
        verify(cognito, never()).changePassword(any(ChangePasswordRequest.class));
    }

    // ------------------------------------------------------------------ 구글 로그인

    @Test
    @DisplayName("구글 시작: fl_oauth(Lax, 10분)에 state·verifier를 두고 로그인 도메인으로 302")
    void google_redirectsToCognito() throws Exception {
        HttpResponse<String> res = get("/api/auth/google", null);
        assertThat(res.statusCode()).isEqualTo(302);
        URI location = URI.create(res.headers().firstValue("Location").orElseThrow());
        assertThat(location.getPath()).isEqualTo("/oauth2/authorize");
        Map<String, String> query = query(location.getRawQuery());
        assertThat(query).containsEntry("identity_provider", "Google")
                .containsEntry("client_id", BFF_CLIENT_ID)
                .containsEntry("response_type", "code")
                .containsEntry("scope", "openid email profile")
                .containsEntry("redirect_uri", FRONTEND + "/api/auth/callback")
                .containsEntry("code_challenge_method", "S256");

        String oauth = setCookies(res).get("fl_oauth");
        assertThat(oauth).contains("Path=/api/auth;").contains("Max-Age=600").contains("HttpOnly").contains("Secure")
                .contains("SameSite=Lax");
        String[] value = oauth.substring("fl_oauth=".length(), oauth.indexOf(';')).split("\\.");
        assertThat(query.get("state")).isEqualTo(value[0]);
        assertThat(query.get("code_challenge")).isEqualTo(s256(value[1]));
    }

    @Test
    @DisplayName("로그아웃 뒤 Cognito 기록 지우기: 로그인 없이 Cognito /logout으로 302, 돌아올 곳은 화면 /login")
    void ssoLogout_redirectsToCognitoLogout() throws Exception {
        HttpResponse<String> res = get("/api/auth/logout/sso", null);
        assertThat(res.statusCode()).isEqualTo(302);
        URI location = URI.create(res.headers().firstValue("Location").orElseThrow());
        assertThat(location.getPath()).isEqualTo("/logout");
        assertThat(query(location.getRawQuery())).containsOnly(
                Map.entry("client_id", BFF_CLIENT_ID),
                Map.entry("logout_uri", FRONTEND + "/login"));
        assertThat(setCookies(res)).isEmpty();
    }

    @Test
    @DisplayName("구글 콜백: 코드를 토큰으로 바꾸고 쿠키 3개 + fl_oauth 삭제, 화면 첫 주소로 302")
    void callback_success() throws Exception {
        String username = "google_1234567890";
        TOKEN_ID.set(idToken(c -> c.claim("cognito:username", username)
                .claim("cognito:groups", List.of(POOL_ID + "_Google", "CUSTOMER")).claim("email", "g@gmail.com")));

        HttpResponse<String> res = get("/api/auth/callback?code=abc&state=st", "fl_oauth=st.verifier-1");

        assertThat(res.statusCode()).isEqualTo(302);
        assertThat(res.headers().firstValue("Location")).contains(FRONTEND + "/");
        Map<String, String> cookies = setCookies(res);
        assertThat(cookies.get("fl_id")).startsWith("fl_id=" + TOKEN_ID.get() + ";");
        assertThat(cookies.get("fl_refresh")).startsWith("fl_refresh=google-refresh;");
        assertThat(cookies.get("fl_uid")).startsWith("fl_uid=" + username + ";");
        assertThat(cookies.get("fl_oauth")).contains("Max-Age=0");

        assertThat(TOKEN_FORM.get()).containsEntry("grant_type", "authorization_code")
                .containsEntry("code", "abc")
                .containsEntry("code_verifier", "verifier-1")
                .containsEntry("client_id", BFF_CLIENT_ID)
                .containsEntry("redirect_uri", FRONTEND + "/api/auth/callback");
        assertThat(TOKEN_AUTH.get()).isEqualTo("Basic " + Base64.getEncoder()
                .encodeToString((BFF_CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("구글 콜백: state가 fl_oauth와 다르거나 fl_oauth가 없으면 400, 코드 교환 안 함")
    void callback_stateMismatch_400() throws Exception {
        HttpResponse<String> res = get("/api/auth/callback?code=abc&state=other", "fl_oauth=st.verifier-1");
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(json.readTree(res.body()).get("code").asText()).isEqualTo("INVALID_OAUTH_STATE");
        assertThat(get("/api/auth/callback?code=abc&state=st", null).statusCode()).isEqualTo(400);
        assertThat(TOKEN_FORM.get()).isNull();
    }

    @Test
    @DisplayName("구글 콜백: 취소했거나 코드 교환이 실패하면 로그인 화면으로 302, 로그인 쿠키 없음")
    void callback_failure_redirectsToLogin() throws Exception {
        HttpResponse<String> canceled = get("/api/auth/callback?error=access_denied&state=st", "fl_oauth=st.v");
        assertThat(canceled.statusCode()).isEqualTo(302);
        assertThat(canceled.headers().firstValue("Location")).contains(FRONTEND + "/login?error=google");
        assertThat(setCookies(canceled)).containsOnlyKeys("fl_oauth");

        TOKEN_STATUS.set(400);
        HttpResponse<String> failed = get("/api/auth/callback?code=used&state=st", "fl_oauth=st.v");
        assertThat(failed.statusCode()).isEqualTo(302);
        assertThat(failed.headers().firstValue("Location")).contains(FRONTEND + "/login?error=google");
        assertThat(setCookies(failed)).containsOnlyKeys("fl_oauth");
    }

    // ------------------------------------------------------------------ 도우미

    HttpResponse<String> post(String path, String body, String cookie) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(uri(path))
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header("X-Requested-With", "freshlink");
        if (cookie != null) {
            req.header("Cookie", cookie);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(uri(path)).GET();
        if (cookie != null) {
            req.header("Cookie", cookie);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** 쿠키 이름 → Set-Cookie 값 전체 */
    static Map<String, String> setCookies(HttpResponse<String> res) {
        Map<String, String> cookies = new LinkedHashMap<>();
        for (String value : res.headers().allValues("Set-Cookie")) {
            cookies.put(value.substring(0, value.indexOf('=')), value);
        }
        return cookies;
    }

    static void assertCleared(HttpResponse<String> res) {
        Map<String, String> cookies = setCookies(res);
        assertThat(cookies).containsKeys("fl_id", "fl_refresh", "fl_uid");
        assertThat(cookies.get("fl_id")).startsWith("fl_id=;").contains("Max-Age=0").contains("Path=/api;");
        assertThat(cookies.get("fl_refresh")).startsWith("fl_refresh=;").contains("Max-Age=0").contains("Path=/api/auth;");
        assertThat(cookies.get("fl_uid")).startsWith("fl_uid=;").contains("Max-Age=0").contains("Path=/api/auth;");
    }

    static Map<String, String> query(String raw) {
        return java.util.Arrays.stream(raw.split("&")).map(p -> p.split("=", 2)).collect(Collectors.toMap(
                p -> URLDecoder.decode(p[0], StandardCharsets.UTF_8),
                p -> URLDecoder.decode(p.length > 1 ? p[1] : "", StandardCharsets.UTF_8)));
    }

    static String secretHash(String username) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(CLIENT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal((username + BFF_CLIENT_ID).getBytes(StandardCharsets.UTF_8)));
    }

    static String s256(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    static AdminInitiateAuthResponse authResult(String idToken, String refreshToken) {
        return authResult(idToken, refreshToken, null);
    }

    static AdminInitiateAuthResponse authResult(String idToken, String refreshToken, String accessToken) {
        return AdminInitiateAuthResponse.builder().authenticationResult(AuthenticationResultType.builder()
                .idToken(idToken).refreshToken(refreshToken).accessToken(accessToken).expiresIn(3600).tokenType("Bearer")
                .build()).build();
    }

    /** Cognito ID 토큰 모양 (aud = bff 클라이언트) */
    static String idToken(Consumer<JWTClaimsSet.Builder> change) throws Exception {
        Instant now = Instant.now();
        String sub = UUID.randomUUID().toString();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(sub)
                .issuer(ISSUER)
                .audience(BFF_CLIENT_ID)
                .claim("token_use", "id")
                .claim("cognito:username", sub)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)));
        change.accept(claims);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).type(JOSEObjectType.JWT).build(),
                claims.build());
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    static RSAKey newKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 가짜 Cognito: /<pool>/.well-known/jwks.json 공개 키, /oauth2/token 코드 교환 */
    static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/" + POOL_ID + "/.well-known/jwks.json",
                    exchange -> reply(exchange, 200, new JWKSet(KEY.toPublicJWK()).toString()));
            server.createContext("/oauth2/token", exchange -> {
                String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                TOKEN_FORM.set(query(form));
                TOKEN_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                if (TOKEN_STATUS.get() != 200) {
                    reply(exchange, TOKEN_STATUS.get(), "{\"error\":\"invalid_grant\"}");
                    return;
                }
                reply(exchange, 200, "{\"id_token\":\"" + TOKEN_ID.get() + "\",\"access_token\":\"a\","
                        + "\"refresh_token\":\"google-refresh\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
