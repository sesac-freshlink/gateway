package com.freshlink.gateway.security;

import com.freshlink.gateway.filter.UserHeaderFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

/**
 * 경로별 인증·역할 규칙 (API 명세 'Gateway 라우팅' 시트, README 3장).
 * <ul>
 *   <li>공개: 점포 상품 목록, 점포 검색, 닉네임 중복 확인(GET만), BFF 로그인 경로(/api/auth/*, me 빼고)</li>
 *   <li>/api/admin/** 은 ADMIN, /api/owner/** 는 OWNER, /api/orders/** 는 CUSTOMER. 그룹이 없으면 403</li>
 *   <li>나머지 /api/** 는 로그인만 확인</li>
 *   <li>/actuator/** 는 클러스터 안(probe·Prometheus·ALB 상태 확인)에서만. ALB·CloudFront를 거친 요청(X-Forwarded-For 있음)은 막는다</li>
 *   <li>그 밖의 경로(/ops/** 등)는 전부 막는다</li>
 *   <li>토큰은 Authorization 헤더가 없으면 fl_id 쿠키에서 읽는다. /api/** 의 POST·PUT·PATCH·DELETE는 X-Requested-With: freshlink가 필요하다(CSRF)</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(CognitoProperties.class)
public class SecurityConfig {

    static final String GROUPS_CLAIM = "cognito:groups";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        RequestMatcher actuator = pathPattern("/actuator/**");
        RequestMatcher fromOutside = request -> request.getHeader("X-Forwarded-For") != null;

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(request -> actuator.matches(request) && fromOutside.matches(request)).denyAll()
                        .requestMatchers(actuator).permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/login",
                                "/api/auth/signup",
                                "/api/auth/confirm",
                                "/api/auth/refresh",
                                "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/google", "/api/auth/callback", "/api/auth/logout/sso").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/stores/{storeId}/products",
                                "/api/stores/{storeId}/products/{productId}",
                                "/api/stores",
                                "/api/members/nickname-availability").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/owner/**").hasRole("OWNER")
                        .requestMatchers("/api/orders/**").hasRole("CUSTOMER")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(new CookieBearerTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((request, response, e) ->
                                ErrorResponseWriter.write(response, 401, "UNAUTHORIZED", "로그인이 필요합니다"))
                        .accessDeniedHandler((request, response, e) ->
                                ErrorResponseWriter.write(response, 403, "FORBIDDEN", "권한이 없습니다")))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                ErrorResponseWriter.write(response, 401, "UNAUTHORIZED", "로그인이 필요합니다"))
                        .accessDeniedHandler((request, response, e) ->
                                ErrorResponseWriter.write(response, 403, "FORBIDDEN", "권한이 없습니다")))
                // 토큰을 보기 전에 CSRF 헤더부터 확인한다
                .addFilterBefore(new CsrfHeaderFilter(), BearerTokenAuthenticationFilter.class)
                // 인가가 끝난 요청에만 사용자 헤더를 붙인다. 위조 헤더 제거도 여기서 한다
                .addFilterAfter(new UserHeaderFilter(), AuthorizationFilter.class);
        return http.build();
    }

    /** 공개 키는 처음 요청 때 받아 캐시한다(기동 때 Cognito에 닿지 않아도 뜬다). */
    @Bean
    JwtDecoder jwtDecoder(CognitoProperties cognito) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(cognito.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(CognitoJwtValidators.of(cognito));
        return decoder;
    }

    /** cognito:groups → ROLE_OWNER·ROLE_CUSTOMER·ROLE_ADMIN */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(GROUPS_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
