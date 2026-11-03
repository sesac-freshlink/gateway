package com.freshlink.gateway.auth;

/**
 * BFF 로그인 API가 돌려주는 오류. 본문은 서비스들과 같은 {code, message, traceId}다.
 * 메시지에는 토큰·비밀번호·Cognito 원문 오류를 넣지 않는다.
 */
public class AuthException extends RuntimeException {

    private final int status;
    private final String code;

    public AuthException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
