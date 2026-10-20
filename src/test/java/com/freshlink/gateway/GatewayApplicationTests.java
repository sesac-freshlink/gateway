package com.freshlink.gateway;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * gateway를 실제 포트로 띄우고, 가짜 Cognito(JWKS)와 가짜 서비스 3개를 붙여 끝에서 끝까지 확인한다.
 * 가짜 서비스는 받은 경로와 헤더를 그대로 돌려준다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayApplicationTests {

    static final String CLIENT_ID = "test-client-id";
    static final RSAKey KEY = newKey("cognito-key");
    static final RSAKey OTHER_KEY = newKey("other-key");

    static final HttpServer COGNITO = start("cognito", exchange -> {
        String body = new JWKSet(KEY.toPublicJWK()).toString();
        reply(exchange, 200, body);
    });
    static final HttpServer MEMBER = start("member-service", null);
    static final HttpServer INVENTORY = start("inventory-service", null);
    static final HttpServer ORDER = start("order-service", null);

    static final String ISSUER = "http://localhost:" + COGNITO.getAddress().getPort() + "/ap-northeast-2_TEST";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("freshlink.cognito.issuer-uri", () -> ISSUER);
        registry.add("freshlink.cognito.client-id", () -> CLIENT_ID);
        registry.add("MEMBER_SERVICE_URI", () -> "http://localhost:" + MEMBER.getAddress().getPort());
        registry.add("INVENTORY_SERVICE_URI", () -> "http://localhost:" + INVENTORY.getAddress().getPort());
        registry.add("ORDER_SERVICE_URI", () -> "http://localhost:" + ORDER.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        List.of(COGNITO, MEMBER, INVENTORY, ORDER).forEach(s -> s.stop(0));
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    // ------------------------------------------------------------------ 401

    @Test
    @DisplayName("토큰이 없으면 401")
    void noToken_401() throws Exception {
        HttpResponse<String> res = get("/api/members/me", null, Map.of());
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(json.readTree(res.body()).get("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("aud가 다르면 401")
    void wrongAudience_401() throws Exception {
        String token = token(KEY, c -> c.audience("other-client"));
        assertThat(get("/api/members/me", token, Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("액세스 토큰이면 401")
    void accessToken_401() throws Exception {
        String token = token(KEY, c -> c.claim("token_use", "access"));
        assertThat(get("/api/members/me", token, Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("발급자가 다르면 401")
    void wrongIssuer_401() throws Exception {
        String token = token(KEY, c -> c.issuer("https://cognito-idp.ap-northeast-2.amazonaws.com/other-pool"));
        assertThat(get("/api/members/me", token, Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("만료됐으면 401")
    void expired_401() throws Exception {
        String token = token(KEY, c -> c.expirationTime(Date.from(Instant.now().minusSeconds(3600))));
        assertThat(get("/api/members/me", token, Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("다른 키로 서명했으면 401")
    void otherKey_401() throws Exception {
        String token = token(OTHER_KEY, c -> { });
        assertThat(get("/api/members/me", token, Map.of()).statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ 403

    @Test
    @DisplayName("고객이 점주 경로를 부르면 403")
    void customerOnOwnerPath_403() throws Exception {
        HttpResponse<String> res = get("/api/owner/inventory", customer(), Map.of());
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(json.readTree(res.body()).get("code").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("관리자도 점주 경로는 403")
    void adminOnOwnerPath_403() throws Exception {
        assertThat(get("/api/owner/orders", groupToken("ADMIN", null), Map.of()).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("점주가 관리자 경로를 부르면 403")
    void ownerOnAdminPath_403() throws Exception {
        assertThat(get("/api/admin/stores", owner("1"), Map.of()).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("고객이 아니면 주문 경로는 403")
    void nonCustomerOnOrdersPath_403() throws Exception {
        assertThat(get("/api/orders/1", owner("1"), Map.of()).statusCode()).isEqualTo(403);
        assertThat(get("/api/orders", groupToken("ADMIN", null), Map.of()).statusCode()).isEqualTo(403);
        assertThat(get("/api/orders/1", token(KEY, c -> { }), Map.of()).statusCode()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ 라우팅·헤더

    @Test
    @DisplayName("점주 요청은 사용자 헤더를 붙여 inventory로 넘긴다")
    void ownerRequest_routedWithUserHeaders() throws Exception {
        String sub = UUID.randomUUID().toString();
        String token = token(KEY, c -> c.subject(sub).claim("cognito:groups", List.of("OWNER")).claim("custom:storeId", "1"));
        HttpResponse<String> res = get("/api/owner/inventory/15/lots", token, Map.of(
                "X-User-Id", "forged-user", "x-store-id", "99", "X-User-Role", "ADMIN"));

        JsonNode seen = seen(res);
        assertThat(seen.get("service").asText()).isEqualTo("inventory-service");
        assertThat(seen.get("path").asText()).isEqualTo("/owner/inventory/15/lots");
        assertThat(header(seen, "X-User-Id")).containsExactly(sub);
        assertThat(header(seen, "X-User-Role")).containsExactly("OWNER");
        assertThat(header(seen, "X-Store-Id")).containsExactly("1");
    }

    @Test
    @DisplayName("storeId가 없으면 X-Store-Id를 붙이지 않고 위조값도 지운다")
    void noStoreId_noStoreHeader_forgedRemoved() throws Exception {
        String sub = UUID.randomUUID().toString();
        String token = token(KEY, c -> c.subject(sub).claim("cognito:groups", List.of("CUSTOMER")));
        HttpResponse<String> res = get("/api/members/me", token, Map.of("X-Store-Id", "1", "X-User-Id", "someone-else"));

        JsonNode seen = seen(res);
        assertThat(seen.get("service").asText()).isEqualTo("member-service");
        assertThat(seen.get("path").asText()).isEqualTo("/members/me");
        assertThat(header(seen, "X-User-Id")).containsExactly(sub);
        assertThat(header(seen, "X-User-Role")).containsExactly("CUSTOMER");
        assertThat(header(seen, "X-Store-Id")).isEmpty();
    }

    @Test
    @DisplayName("공개 경로는 토큰 없이 통과하고 위조 헤더를 지운다")
    void publicPaths_passWithoutToken_forgedRemoved() throws Exception {
        HttpResponse<String> res = get("/api/stores/1/products", null, Map.of("X-User-Id", "forged", "X-User-Role", "OWNER"));
        JsonNode seen = seen(res);
        assertThat(seen.get("service").asText()).isEqualTo("inventory-service");
        assertThat(seen.get("path").asText()).isEqualTo("/stores/1/products");
        assertThat(header(seen, "X-User-Id")).isEmpty();
        assertThat(header(seen, "X-User-Role")).isEmpty();

        assertThat(seen(get("/api/stores?region=SEOUL", null, Map.of())).get("service").asText()).isEqualTo("member-service");
        assertThat(seen(get("/api/members/nickname-availability?nickname=Fresh01", null, Map.of())).get("path").asText())
                .isEqualTo("/members/nickname-availability");
    }

    @Test
    @DisplayName("공개 경로라도 GET이 아니면 로그인이 필요하다")
    void publicPathNonGet_401() throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(uri("/api/stores"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).header("Content-Type", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("주문은 order로 가고 Idempotency-Key를 그대로 넘긴다")
    void orders_routedWithIdempotencyKey() throws Exception {
        String key = UUID.randomUUID().toString();
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(uri("/api/orders"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + customer())
                .header("Idempotency-Key", key).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode seen = seen(res);
        assertThat(seen.get("service").asText()).isEqualTo("order-service");
        assertThat(seen.get("method").asText()).isEqualTo("POST");
        assertThat(header(seen, "Idempotency-Key")).containsExactly(key);
    }

    @Test
    @DisplayName("점주 주문과 관리자 경로")
    void ownerOrdersAndAdminRoutes() throws Exception {
        assertThat(seen(get("/api/owner/orders", owner("2"), Map.of())).get("service").asText()).isEqualTo("order-service");
        assertThat(seen(get("/api/owner/disposals", owner("2"), Map.of())).get("service").asText()).isEqualTo("inventory-service");
        JsonNode admin = seen(get("/api/admin/stores?page=0", groupToken("ADMIN", null), Map.of()));
        assertThat(admin.get("service").asText()).isEqualTo("member-service");
        assertThat(admin.get("path").asText()).isEqualTo("/admin/stores");
    }

    // ------------------------------------------------------------------ 추적 (할 일 16)

    @Test
    @DisplayName("서비스로 넘길 때 traceparent를 붙이고, 들어온 추적을 잇는다")
    void tracing_traceparentForwarded() throws Exception {
        List<String> fresh = header(seen(get("/api/stores/1/products", null, Map.of())), "traceparent");
        assertThat(fresh).hasSize(1);
        assertThat(fresh.get(0)).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");

        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        List<String> joined = header(seen(get("/api/stores/1/products", null,
                Map.of("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01"))), "traceparent");
        assertThat(joined).hasSize(1);
        assertThat(joined.get(0)).startsWith("00-" + traceId + "-");
    }

    // ------------------------------------------------------------------ actuator·그 밖

    @Test
    @DisplayName("actuator는 클러스터 안에서만 열린다")
    void actuator_onlyInsideCluster() throws Exception {
        assertThat(get("/actuator/health/readiness", null, Map.of()).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health", null, Map.of("X-Forwarded-For", "203.0.113.10")).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/prometheus", null, Map.of("X-Forwarded-For", "203.0.113.10")).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("api 밖의 경로는 막는다")
    void pathsOutsideApi_denied() throws Exception {
        assertThat(get("/ops/orders/1/close", customer(), Map.of()).statusCode()).isEqualTo(403);
        assertThat(get("/members/me", customer(), Map.of()).statusCode()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ 도우미

    String customer() throws Exception {
        return groupToken("CUSTOMER", null);
    }

    String owner(String storeId) throws Exception {
        return groupToken("OWNER", storeId);
    }

    String groupToken(String group, String storeId) throws Exception {
        return token(KEY, c -> {
            c.claim("cognito:groups", List.of(group));
            if (storeId != null) {
                c.claim("custom:storeId", storeId);
            }
        });
    }

    /** Cognito ID 토큰 모양. change로 한 가지씩 틀리게 만든다 */
    static String token(RSAKey key, Consumer<JWTClaimsSet.Builder> change) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience(CLIENT_ID)
                .claim("token_use", "id")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)));
        change.accept(claims);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),
                claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    HttpResponse<String> get(String path, String token, Map<String, String> headers) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(uri(path)).GET();
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        headers.forEach(req::header);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    JsonNode seen(HttpResponse<String> res) throws Exception {
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json.readTree(res.body());
    }

    static List<String> header(JsonNode seen, String name) {
        List<String> values = new ArrayList<>();
        seen.get("headers").properties().forEach(e -> {
            if (e.getKey().equalsIgnoreCase(name)) {
                e.getValue().forEach(v -> values.add(v.asText()));
            }
        });
        return values;
    }

    static RSAKey newKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** handler가 없으면 받은 메서드·경로·헤더를 JSON으로 돌려주는 가짜 서비스 */
    static HttpServer start(String name, com.sun.net.httpserver.HttpHandler handler) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", handler != null ? handler : exchange -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("service", name);
                body.put("method", exchange.getRequestMethod());
                body.put("path", exchange.getRequestURI().getPath());
                body.put("headers", exchange.getRequestHeaders());
                reply(exchange, 200, new ObjectMapper().writeValueAsString(body));
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
