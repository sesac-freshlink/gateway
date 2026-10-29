package com.freshlink.gateway.security;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Cognito 값. 모두 환경 변수로 받는다(클러스터에서는 ArgoCD·gitops가 넣는다).
 * 토큰 검증에는 issuerUri·clientId만 쓰고, 나머지는 BFF 로그인(auth 패키지)이 쓴다.
 *
 * @param issuerUri            발급자 주소 {@code https://cognito-idp.ap-northeast-2.amazonaws.com/<UserPoolId>}. 토큰의 iss와 같아야 하고, 공개 키는 여기에 /.well-known/jwks.json을 붙여 받는다
 * @param clientId             앱 클라이언트 ID. ID 토큰의 aud와 같아야 한다
 * @param bffClientId          시크릿이 있는 bff 클라이언트 ID. 로그인·갱신·가입·구글 로그인에 쓴다
 * @param clientSecret         bff 클라이언트 시크릿. SECRET_HASH와 토큰 취소·코드 교환에 쓴다
 * @param userPoolId           User Pool ID (AdminInitiateAuth)
 * @param domainUrl            로그인 도메인 {@code https://freshlink-<env>-auth.auth.ap-northeast-2.amazoncognito.com} (구글 로그인)
 * @param region               AWS 리전
 * @param refreshTokenValidity 리프레시 토큰 기간. fl_refresh·fl_uid 쿠키 만료로 쓴다
 */
@Validated
@ConfigurationProperties("freshlink.cognito")
public record CognitoProperties(
        @NotBlank String issuerUri,
        @NotBlank String clientId,
        String bffClientId,
        String clientSecret,
        String userPoolId,
        String domainUrl,
        String region,
        Duration refreshTokenValidity) {

    public String jwkSetUri() {
        return issuerUri.replaceAll("/+$", "") + "/.well-known/jwks.json";
    }

    /** 시크릿이 로그나 오류 메시지에 찍히지 않게 한다 */
    @Override
    public String toString() {
        return "CognitoProperties[issuerUri=" + issuerUri + ", clientId=" + clientId + ", bffClientId=" + bffClientId
                + ", clientSecret=" + (clientSecret == null || clientSecret.isEmpty() ? "(없음)" : "****")
                + ", userPoolId=" + userPoolId + ", domainUrl=" + domainUrl + ", region=" + region
                + ", refreshTokenValidity=" + refreshTokenValidity + "]";
    }
}
