package com.freshlink.gateway.auth;

import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 로그인·갱신·me 응답 본문. 토큰은 넣지 않는다(쿠키에만 있다).
 *
 * @param userId  Cognito sub
 * @param role    ADMIN·OWNER·CUSTOMER 중 하나. 그룹이 여럿이면 앞쪽(ADMIN > OWNER > CUSTOMER)
 * @param storeId 점주만 있다 (custom:storeId)
 * @param email   이메일
 */
public record AuthUser(String userId, String role, String storeId, String email) {

    /** 화면 claims.ts와 같은 순서 */
    static final List<String> ROLES = List.of("ADMIN", "OWNER", "CUSTOMER");

    /** 역할을 알 수 없으면 null */
    static AuthUser from(Jwt idToken) {
        List<String> groups = idToken.getClaimAsStringList("cognito:groups");
        if (groups == null || idToken.getSubject() == null) {
            return null;
        }
        String role = ROLES.stream().filter(groups::contains).findFirst().orElse(null);
        if (role == null) {
            return null;
        }
        return new AuthUser(idToken.getSubject(), role,
                idToken.getClaimAsString("custom:storeId"), idToken.getClaimAsString("email"));
    }
}
