package com.freshlink.gateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 서비스로 넘기는 사용자 헤더를 만든다.
 * <ol>
 *   <li>클라이언트가 보낸 X-User-Id·X-User-Role·X-Store-Id는 항상 지운다(위조 방지, 공개 경로 포함)</li>
 *   <li>검증된 토큰이 있으면 sub → X-User-Id, cognito:groups → X-User-Role(쉼표로 이음), custom:storeId → X-Store-Id</li>
 *   <li>custom:storeId가 없으면 X-Store-Id를 붙이지 않는다(서비스가 403 STORE_NOT_LINKED로 처리)</li>
 * </ol>
 */
public class UserHeaderFilter extends OncePerRequestFilter {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLE = "X-User-Role";
    public static final String STORE_ID = "X-Store-Id";

    private static final Set<String> TRUSTED = Set.of(
            USER_ID.toLowerCase(Locale.ROOT), USER_ROLE.toLowerCase(Locale.ROOT), STORE_ID.toLowerCase(Locale.ROOT));

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Map<String, String> added = new LinkedHashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            added.put(USER_ID, jwt.getSubject());
            List<String> groups = jwt.getClaimAsStringList("cognito:groups");
            if (groups != null && !groups.isEmpty()) {
                added.put(USER_ROLE, String.join(",", groups));
            }
            String storeId = jwt.getClaimAsString("custom:storeId");
            if (StringUtils.hasText(storeId)) {
                added.put(STORE_ID, storeId);
            }
        }
        chain.doFilter(new UserHeaderRequest(request, added), response);
    }

    /** 신뢰 헤더는 토큰에서 만든 값만 보이고, 나머지 헤더는 그대로 보이는 요청 */
    static final class UserHeaderRequest extends HttpServletRequestWrapper {

        private final Map<String, String> added;

        UserHeaderRequest(HttpServletRequest request, Map<String, String> added) {
            super(request);
            this.added = added;
        }

        private String addedValue(String name) {
            for (Map.Entry<String, String> e : added.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return null;
        }

        private static boolean trusted(String name) {
            return name != null && TRUSTED.contains(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public String getHeader(String name) {
            return trusted(name) ? addedValue(name) : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (!trusted(name)) {
                return super.getHeaders(name);
            }
            String value = addedValue(name);
            return value == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(value));
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Enumeration<String> e = super.getHeaderNames(); e.hasMoreElements(); ) {
                String name = e.nextElement();
                if (!trusted(name)) {
                    names.add(name);
                }
            }
            names.addAll(added.keySet());
            return Collections.enumeration(names);
        }
    }
}
