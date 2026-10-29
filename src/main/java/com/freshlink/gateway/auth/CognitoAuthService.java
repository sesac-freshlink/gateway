package com.freshlink.gateway.auth;

import com.freshlink.gateway.security.CognitoProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AliasExistsException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CodeMismatchException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ConfirmSignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ExpiredCodeException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidParameterException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.LimitExceededException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.PasswordResetRequiredException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RevokeTokenRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.TooManyFailedAttemptsException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.TooManyRequestsException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotConfirmedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

/**
 * gateway가 브라우저 대신 Cognito를 부른다. 시크릿이 있는 bff 클라이언트를 쓰고, 호출마다 SECRET_HASH를 붙인다.
 * <ul>
 *   <li>로그인·갱신: AdminInitiateAuth (IRSA 권한 cognito-idp:AdminInitiateAuth)</li>
 *   <li>가입·인증 코드·토큰 취소: SignUp, ConfirmSignUp, RevokeToken (시크릿만으로 된다)</li>
 *   <li>구글 로그인 코드 교환: 로그인 도메인 /oauth2/token (Basic 인증)</li>
 * </ul>
 * Cognito 오류는 화면이 안내할 수 있는 코드로 바꾼다. 비밀번호·토큰·Cognito 원문 메시지는 로그·응답에 남기지 않는다.
 */
@Service
public class CognitoAuthService {

    private static final Logger log = LoggerFactory.getLogger(CognitoAuthService.class);

    private final CognitoIdentityProviderClient cognito;
    private final CognitoProperties props;
    private final RestClient tokenClient;

    public CognitoAuthService(CognitoIdentityProviderClient cognito, CognitoProperties props) {
        this.cognito = cognito;
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.tokenClient = RestClient.builder().requestFactory(factory).build();
    }

    /** Cognito가 준 토큰. refreshToken은 갱신 때 새로 오지 않으면 null */
    public record Tokens(String idToken, String refreshToken, Duration expiresIn) {
    }

    /** 이메일·비밀번호 로그인 (사용자 이름 = 이메일) */
    public Tokens login(String email, String password) {
        try {
            AdminInitiateAuthResponse res = cognito.adminInitiateAuth(AdminInitiateAuthRequest.builder()
                    .userPoolId(props.userPoolId())
                    .clientId(props.bffClientId())
                    .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                    .authParameters(Map.of(
                            "USERNAME", email,
                            "PASSWORD", password,
                            "SECRET_HASH", secretHash(email)))
                    .build());
            if (res.challengeName() != null) {
                // 점주·관리자는 영구 비밀번호로 만들어서 보통은 오지 않는다
                log.warn("로그인 추가 단계 요구 challenge={}", res.challengeNameAsString());
                throw new AuthException(403, "CHALLENGE_REQUIRED", "추가 단계가 필요한 계정입니다. 테스트 담당자에게 문의해 주세요");
            }
            return tokens(res.authenticationResult());
        } catch (NotAuthorizedException | UserNotFoundException e) {
            throw new AuthException(401, "INVALID_CREDENTIALS", "이메일 또는 비밀번호를 확인해 주세요");
        } catch (UserNotConfirmedException e) {
            throw new AuthException(403, "USER_NOT_CONFIRMED", "이메일 인증이 필요합니다");
        } catch (PasswordResetRequiredException e) {
            throw new AuthException(403, "PASSWORD_RESET_REQUIRED", "비밀번호를 다시 정해야 하는 계정입니다");
        } catch (SdkException e) {
            throw mapCommon(e, "login");
        }
    }

    /** 가입. Cognito에는 email만 둔다(이름은 member DB, 10/21 결정). 역할은 가입 확인 Lambda가 CUSTOMER로 넣는다 */
    public void signUp(String email, String password) {
        try {
            cognito.signUp(SignUpRequest.builder()
                    .clientId(props.bffClientId())
                    .secretHash(secretHash(email))
                    .username(email)
                    .password(password)
                    .userAttributes(AttributeType.builder().name("email").value(email).build())
                    .build());
        } catch (UsernameExistsException | AliasExistsException e) {
            throw new AuthException(409, "EMAIL_ALREADY_EXISTS", "이미 등록되었거나 가입이 진행 중인 이메일입니다");
        } catch (InvalidPasswordException e) {
            throw new AuthException(400, "INVALID_PASSWORD", "비밀번호 규칙을 확인해 주세요");
        } catch (InvalidParameterException e) {
            throw new AuthException(400, "INVALID_REQUEST", "입력 값을 확인해 주세요");
        } catch (SdkException e) {
            throw mapCommon(e, "signup");
        }
    }

    /** 이메일로 받은 인증 코드 확인 */
    public void confirmSignUp(String email, String code) {
        try {
            cognito.confirmSignUp(ConfirmSignUpRequest.builder()
                    .clientId(props.bffClientId())
                    .secretHash(secretHash(email))
                    .username(email)
                    .confirmationCode(code)
                    .build());
        } catch (CodeMismatchException e) {
            throw new AuthException(400, "CODE_MISMATCH", "인증 코드가 맞지 않습니다");
        } catch (ExpiredCodeException e) {
            throw new AuthException(400, "CODE_EXPIRED", "인증 코드가 만료됐습니다");
        } catch (NotAuthorizedException | UserNotFoundException e) {
            // 이미 인증한 계정이거나 없는 계정이다. 어느 쪽인지는 알려 주지 않는다
            throw new AuthException(400, "CODE_MISMATCH", "인증 코드가 맞지 않습니다");
        } catch (SdkException e) {
            throw mapCommon(e, "confirm");
        }
    }

    /**
     * ID 토큰 갱신. SECRET_HASH의 사용자 이름은 이메일이 아니라 토큰의 cognito:username이다
     * (이메일 가입자는 sub UUID, 구글 가입자는 google_숫자). 실패하면 401
     */
    public Tokens refresh(String refreshToken, String username) {
        try {
            AdminInitiateAuthResponse res = cognito.adminInitiateAuth(AdminInitiateAuthRequest.builder()
                    .userPoolId(props.userPoolId())
                    .clientId(props.bffClientId())
                    .authFlow(AuthFlowType.REFRESH_TOKEN_AUTH)
                    .authParameters(Map.of(
                            "REFRESH_TOKEN", refreshToken,
                            "SECRET_HASH", secretHash(username)))
                    .build());
            if (res.authenticationResult() == null) {
                throw new AuthException(401, "SESSION_EXPIRED", "다시 로그인해 주세요");
            }
            return tokens(res.authenticationResult());
        } catch (NotAuthorizedException | UserNotFoundException e) {
            throw new AuthException(401, "SESSION_EXPIRED", "다시 로그인해 주세요");
        } catch (SdkException e) {
            throw mapCommon(e, "refresh");
        }
    }

    /** 리프레시 토큰 취소. 실패해도 로그아웃은 이어 가야 해서 예외를 던지지 않는다 */
    public void revoke(String refreshToken) {
        try {
            cognito.revokeToken(RevokeTokenRequest.builder()
                    .token(refreshToken)
                    .clientId(props.bffClientId())
                    .clientSecret(props.clientSecret())
                    .build());
        } catch (SdkException e) {
            log.warn("토큰 취소 실패 (쿠키는 지운다) error={}", e.getClass().getSimpleName());
        }
    }

    /** 구글 로그인 뒤 받은 코드를 토큰으로 바꾼다. 실패하면 null (화면은 로그인 화면으로 돌아간다) */
    public Tokens exchangeCode(String code, String codeVerifier, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", props.bffClientId());
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("code_verifier", codeVerifier);
        try {
            TokenEndpointResponse res = tokenClient.post()
                    .uri(props.domainUrl().replaceAll("/+$", "") + "/oauth2/token")
                    .headers(h -> h.setBasicAuth(props.bffClientId(), props.clientSecret(), StandardCharsets.UTF_8))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenEndpointResponse.class);
            if (res == null || res.id_token() == null || res.expires_in() == null) {
                log.warn("구글 코드 교환 응답에 ID 토큰이 없다");
                return null;
            }
            return new Tokens(res.id_token(), res.refresh_token(), Duration.ofSeconds(res.expires_in()));
        } catch (RestClientException e) {
            log.warn("구글 코드 교환 실패 error={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** /oauth2/token 응답 (칸 이름은 OAuth 표준 그대로) */
    record TokenEndpointResponse(String id_token, String refresh_token, Integer expires_in) {
    }

    /** Base64(HMAC-SHA256(키 = 클라이언트 시크릿, 메시지 = 사용자 이름 + bff 클라이언트 ID)) */
    String secretHash(String username) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.clientSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal((username + props.bffClientId()).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException | IllegalArgumentException e) {
            throw new IllegalStateException("SECRET_HASH를 만들 수 없습니다. COGNITO_CLIENT_SECRET을 확인하세요");
        }
    }

    private static Tokens tokens(AuthenticationResultType result) {
        return new Tokens(result.idToken(), result.refreshToken(), Duration.ofSeconds(result.expiresIn()));
    }

    private static AuthException mapCommon(SdkException e, String action) {
        if (e instanceof TooManyRequestsException || e instanceof LimitExceededException
                || e instanceof TooManyFailedAttemptsException) {
            return new AuthException(429, "TOO_MANY_REQUESTS", "요청이 너무 많습니다. 잠시 뒤 다시 시도해 주세요");
        }
        log.error("Cognito 호출 실패 action={} error={}", action, e.getClass().getSimpleName());
        return new AuthException(502, "AUTH_UNAVAILABLE", "로그인 서버에 연결하지 못했습니다. 잠시 뒤 다시 시도해 주세요");
    }
}
