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
 * @param passwordLogin 이메일·비밀번호로 가입한 계정인지. 구글 가입 계정은 false (비밀번호 변경 메뉴를 숨긴다, A4)
 */
public record AuthUser(String userId, String role, String storeId, String email, boolean passwordLogin) {

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
                idToken.getClaimAsString("custom:storeId"), idToken.getClaimAsString("email"), !isFederated(idToken));
    }

    /** 관리자 그룹에 든 계정인지 (A4: 관리자는 비밀번호 변경 대상이 아니다) */
    static boolean isAdmin(Jwt idToken) {
        List<String> groups = idToken.getClaimAsStringList("cognito:groups");
        return groups != null && groups.contains("ADMIN");
    }

    /**
     * 구글 등 외부 로그인으로 만든 계정인지. Cognito는 이런 계정의 ID 토큰에 identities를 넣고
     * {userPoolId}_Google 같은 그룹에 넣는다
     */
    static boolean isFederated(Jwt idToken) {
        if (idToken.hasClaim("identities")) {
            return true;
        }
        List<String> groups = idToken.getClaimAsStringList("cognito:groups");
        return groups != null && groups.stream().anyMatch(g -> g.endsWith("_Google"));
    }
}
