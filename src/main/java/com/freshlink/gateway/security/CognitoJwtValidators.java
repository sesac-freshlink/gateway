package com.freshlink.gateway.security;

import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;

/**
 * Cognito ID 토큰 검증 규칙. 서명(RS256)은 디코더가 공개 키로 확인하고, 여기서는 클레임을 확인한다.
 * 하나라도 틀리면 401이다.
 */
public final class CognitoJwtValidators {

    private CognitoJwtValidators() {
    }

    public static OAuth2TokenValidator<Jwt> of(CognitoProperties cognito) {
        return new DelegatingOAuth2TokenValidator<>(
                // exp·nbf(시계 오차 60초)와 iss
                JwtValidators.createDefaultWithIssuer(cognito.issuerUri()),
                // 액세스 토큰에는 custom:storeId가 없다. 프론트는 ID 토큰을 보낸다
                new JwtClaimValidator<String>("token_use", "id"::equals),
                audience(cognito.clientId()));
    }

    private static OAuth2TokenValidator<Jwt> audience(String clientId) {
        OAuth2Error error = new OAuth2Error("invalid_token", "aud가 앱 클라이언트 ID와 다르다", null);
        return jwt -> {
            List<String> aud = jwt.getAudience();
            return aud != null && aud.contains(clientId)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(error);
        };
    }
}
