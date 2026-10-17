package com.freshlink.gateway.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.MDC;
import org.springframework.http.MediaType;

/**
 * gateway가 직접 돌려주는 401·403 본문. 서비스들과 같은 모양 {code, message, traceId}로 쓴다.
 * 토큰 원문이나 실패 이유 상세는 본문·로그에 남기지 않는다.
 */
final class ErrorResponseWriter {

    private ErrorResponseWriter() {
    }

    static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        String traceId = MDC.get("traceId");
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"traceId\":"
                + (traceId == null ? "null" : "\"" + traceId + "\"") + "}");
    }
}
