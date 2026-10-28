package com.freshlink.gateway.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Cognito 토큰 검증 값. 둘 다 환경 변수로 받는다(클러스터에서는 ArgoCD가 넣는다).
 *
 * @param issuerUri 발급자 주소 {@code https://cognito-idp.ap-northeast-2.amazonaws.com/<UserPoolId>}. 토큰의 iss와 같아야 하고, 공개 키는 여기에 /.well-known/jwks.json을 붙여 받는다
 * @param clientId  앱 클라이언트 ID. ID 토큰의 aud와 같아야 한다
 */
@Validated
@ConfigurationProperties("freshlink.cognito")
public record CognitoProperties(@NotBlank String issuerUri, @NotBlank String clientId) {

    public String jwkSetUri() {
        return issuerUri.replaceAll("/+$", "") + "/.well-known/jwks.json";
    }
}
