package com.freshlink.gateway.proxy;

import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 서비스로 넘기는 HTTP 클라이언트(Apache HttpClient 5) 연결 풀.
 *
 * <ul>
 *   <li>기본값은 서비스(주소) 하나에 연결 5개다. SSE(알림 종, 점주 매출)는 화면을 열어 둔 동안 연결 하나를 계속 잡고 있어서,
 *       SSE가 5개 넘게 열리면 그 서비스로 가는 다른 요청이 빈 연결을 3분 기다리다 504가 됐다(11/2 dev 알림 504).</li>
 *   <li>서비스 하나에 {@value #MAX_PER_SERVICE}개, 전체 {@value #MAX_TOTAL}개까지 연다. 화면 하나가 SSE를 2개까지 연다(알림, 점주 매출).</li>
 *   <li>풀이 그래도 모자라면 {@value #ACQUIRE_TIMEOUT_SECONDS}초만 기다리고 실패한다(3분씩 붙잡지 않는다).</li>
 * </ul>
 * 읽기 타임아웃은 여전히 넣지 않는다(SSE가 끊긴다. application.yml SSE 주석).
 */
@Configuration
public class ProxyHttpClientConfig {

    static final int MAX_PER_SERVICE = 200;
    static final int MAX_TOTAL = 500;
    static final long ACQUIRE_TIMEOUT_SECONDS = 5;

    @Bean
    ClientHttpRequestFactoryBuilder<?> clientHttpRequestFactoryBuilder() {
        return ClientHttpRequestFactoryBuilder.httpComponents()
                .withConnectionManagerCustomizer(pool -> pool.setMaxConnPerRoute(MAX_PER_SERVICE).setMaxConnTotal(MAX_TOTAL))
                .withDefaultRequestConfigCustomizer(request ->
                        request.setConnectionRequestTimeout(Timeout.ofSeconds(ACQUIRE_TIMEOUT_SECONDS)));
    }
}
