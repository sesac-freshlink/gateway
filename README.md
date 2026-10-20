# gateway

> FreshLink의 **모든 API 요청이 지나가는 입구**. 요청을 알맞은 서비스로 보내고(라우팅), Amazon Cognito가 발급한 로그인 토큰(JWT)이 진짜인지 확인한다.

| 항목 | 내용 |
| --- | --- |
| 담당 | 김진아 |
| 관련 기능 ID | C-03 역할별 권한 분리 |
| 기술 | Java 21, Spring Boot 4.0.x, Spring Cloud Gateway **WebMVC**(서블릿판, Spring Cloud 2025.1.x), Spring Security OAuth2 Resource Server (Cognito JWKS, RS256) |
| 관측성 | Actuator·Micrometer → Prometheus (앱 포트의 `/actuator/prometheus`, 관리 포트를 따로 두지 않는다. 모든 서비스가 8080) · Micrometer Tracing → Zipkin · JSON 로그(traceId 포함) → Alloy → Loki ([gitops/platform/observability](https://github.com/sesac-freshlink/gitops/tree/main/platform/observability)) |
| 배포 | GitHub Actions → ECR `gateway` → gitops → ArgoCD → EKS (`freshlink` 네임스페이스, 파드 2개) |

---

## 1. FreshLink는 무엇인가

편의점 점주와 고객이 **같은 재고 데이터**를 보는 서비스다. 고객이 결제하면 점주 화면의 재고가 정확하게 줄고, 유통기한이 지난 상품은 고객에게 팔리지 않는다. 전체 소개는 [Organization README](https://github.com/sesac-freshlink)를 참고한다.

## 2. 아키텍처에서의 위치

```mermaid
flowchart LR
    U["사용자 화면<br/>(Amplify Auth)"] -- "가입·로그인" --> COG["Amazon Cognito<br/>토큰 발급"]
    U -- "Bearer ID 토큰" --> CF["CloudFront<br/>/api/*"]
    CF --> ALB["ALB (EKS Ingress)"]
    ALB --> GW["gateway<br/>라우팅 · JWT 검증"]
    GW -. "공개 키(JWKS) 받기, 캐시" .-> COG
    GW --> MEM["member-service"]
    GW --> INV["inventory-service"]
    GW --> ORD["order-service"]
```

사용자는 CloudFront 주소 하나로 접속한다. 화면은 S3에서, `/api/*` 요청은 ALB를 거쳐 이 게이트웨이로 들어온다. 게이트웨이는 **DB도, 비밀 키도 갖지 않는다.** Cognito가 개인 키로 서명한 토큰을 Cognito의 공개 키(JWKS)로 검증하기 때문이다.

> 비유: Cognito가 놀이공원 매표소(신분 확인 후 손목띠 채우기)라면, gateway는 놀이기구 입구 직원(손목띠가 진짜인지, 오늘 날짜인지만 확인)이다.

> **변경 (2026-10-13):** 처음에는 member-service가 HS256 JWT를 발급하고 gateway가 같은 비밀 키로 검증하기로 했으나, 발급을 Amazon Cognito로 옮겼다. Keycloak과의 비교와 이유는 [infra/docs/기술스택과 선택이유.md](https://github.com/sesac-freshlink/infra/blob/main/docs/기술스택과%20선택이유.md) 3-7.

## 3. 하는 일

1. **라우팅** (`application.yml`, 위에서부터 먼저 매칭. 모두 `/api`를 떼고 넘긴다)

   | 순서 | 경로 | 메서드 | 보내는 곳 | 인증 | 역할 (아니면 403) |
   | --- | --- | --- | --- | --- | --- |
   | 1 | `/api/stores/{storeId}/products` | GET | inventory-service | 공개 | |
   | 2 | `/api/stores` | GET | member-service | 공개 | |
   | 3 | `/api/members/nickname-availability` | GET | member-service | 공개 | |
   | 4 | `/api/members/**` | 전부 | member-service | 로그인 | |
   | 5 | `/api/admin/**` | 전부 | member-service | 로그인 | ADMIN |
   | 6 | `/api/owner/inventory/**`, `/api/owner/disposals` | 전부 | inventory-service | 로그인 | OWNER |
   | 7 | `/api/owner/orders` | 전부 | order-service | 로그인 | OWNER |
   | 8 | `/api/orders/**` | 전부 | order-service | 로그인 | CUSTOMER |
   | - | `/ops/**`, 그 밖의 경로 | - | 라우트 없음 | 막음 | |

   서비스 주소는 기본값이 쿠버네티스 Service 이름(`http://member-service:8080` 등)이고, `MEMBER_SERVICE_URI`·`INVENTORY_SERVICE_URI`·`ORDER_SERVICE_URI`로 바꿀 수 있다. `Idempotency-Key`·`Authorization` 같은 다른 헤더는 그대로 넘긴다.

2. **공개 경로 예외:** 위 표의 GET 3개만 토큰 없이 통과시킨다. 같은 경로라도 GET이 아니면 로그인이 필요하다. 회원가입·로그인은 브라우저가 Cognito로 바로 가므로 gateway를 거치지 않는다.
3. **토큰 검증:** Cognito 공개 키(JWKS)로 RS256 서명, 만료(`exp`), 발급자(`iss`), `token_use = id`, `aud`(= 앱 클라이언트 ID)를 확인한다. 하나라도 틀리면 `401`.
4. **역할 1차 확인:** `/api/owner/**`는 `cognito:groups`에 `OWNER`, `/api/admin/**`는 `ADMIN`, `/api/orders/**`는 `CUSTOMER`가 있어야 통과, 아니면 `403`.
5. **사용자 정보 전달:** 토큰에서 꺼낸 값을 헤더로 붙여 넘긴다. `sub` → `X-User-Id`, `cognito:groups` → `X-User-Role`(그룹이 여럿이면 쉼표로 잇는다), `custom:storeId` → `X-Store-Id`(값이 있을 때만).
6. **위조 헤더 제거:** 클라이언트가 보낸 `X-User-*`, `X-Store-Id`는 **먼저 지우고** 다시 채운다. 빠뜨리면 다른 사람인 척 요청할 수 있다.
7. **Actuator 보호:** Actuator는 앱 포트(8080)에 있다. `/actuator/**`로 가는 라우트는 만들지 않는다. ALB·CloudFront를 거친 요청(`X-Forwarded-For` 헤더가 있음)은 `/actuator`를 막고(401), 클러스터 안 요청(probe, Prometheus, ALB 상태 확인)만 허용한다. ALB Ingress는 `/api`만 이 게이트웨이로 넘긴다.

401·403 본문은 서비스와 같은 모양이다: `{"code":"UNAUTHORIZED","message":"로그인이 필요합니다","traceId":null}`, `{"code":"FORBIDDEN","message":"권한이 없습니다","traceId":null}`. `traceId`는 할 일 16(Micrometer Tracing → Zipkin)에서 채워진다.

'이 lot이 내 점포 것인가', '이 주문이 내 주문인가' 같은 **소유권 확인은 각 서비스가** 헤더와 DB를 비교해서 한다.

### JWT 규칙 (Cognito ID 토큰)

| 항목 | 값 |
| --- | --- |
| 발급 | Amazon Cognito User Pool `freshlink-users` ([infra](https://github.com/sesac-freshlink/infra) `modules/cognito`) |
| 서명 | RS256. 공개 키는 `https://cognito-idp.ap-northeast-2.amazonaws.com/<UserPoolId>/.well-known/jwks.json`에서 받아 캐시한다. 공유 비밀 키 없음 |
| 보내는 토큰 | 프론트는 **ID 토큰**을 `Authorization: Bearer`로 보낸다. `custom:storeId`는 ID 토큰에만 들어가기 때문이다 |
| 쓰는 클레임 | `sub`(UUID), `cognito:groups`(OWNER/CUSTOMER), `custom:storeId`(점주만), `token_use`(= `id`), `aud`(= 앱 클라이언트 ID), `iss`, `exp` |
| 만료 | ID 토큰 1시간, 리프레시 토큰 1일 (갱신은 프론트의 Amplify가 한다) |

`X-User-Id`는 숫자가 아니라 UUID 문자열이다. 뒤 서비스는 사용자 ID 칸을 `VARCHAR(36)`으로 둔다.

설정은 환경 변수 두 개다. 클러스터에서는 infra가 ArgoCD root에 넘긴 값을 파드에 넣는다. 공개 키는 첫 요청 때 받아 캐시하므로 Cognito에 닿지 않아도 기동은 된다(파드는 NAT를 통해 Cognito에 닿는다).

```yaml
freshlink:
  cognito:
    issuer-uri: ${COGNITO_ISSUER_URI}   # https://cognito-idp.ap-northeast-2.amazonaws.com/<UserPoolId>
    client-id: ${COGNITO_CLIENT_ID}     # 토큰 aud와 같아야 한다
```

## 4. 폴더 구조

```
gateway/
├── build.gradle                       # Boot 4.0.8, Spring Cloud 2025.1.3, Gateway WebMVC + Actuator
├── .github/workflows/ci.yml          # CI 호출부 (.github 레포 spring-build.yml)
├── src/main/java/com/freshlink/gateway/
│   ├── GatewayApplication.java
│   ├── filter/UserHeaderFilter.java   # 위조 헤더 제거, 사용자 헤더 주입
│   └── security/    # SecurityConfig(공개 경로·역할 규칙), CognitoJwtValidators(iss·token_use·aud), CognitoProperties
├── src/main/resources/
│   ├── application.yml                # 라우팅 표, Cognito 값(환경 변수)
│   ├── application-dev.yml            # 로그 레벨만 다름
│   └── application-prod.yml
└── src/test/java/com/freshlink/gateway/
    └── GatewayApplicationTests.java   # 가짜 Cognito(JWKS)·가짜 서비스로 401·403·라우팅·위조 헤더 제거 확인
```

### Gateway WebMVC판을 쓰는 이유 (2026-10-15 팀 합의)

Spring Cloud Gateway는 WebFlux(리액티브)판과 WebMVC(서블릿)판 두 가지가 있다. 처음에는 WebFlux판으로 적었으나, 팀이 배운 기술은 서블릿 기반 Spring MVC이고 서비스 3개도 같은 바탕이라 WebMVC판으로 바꿨다. 라우팅·필터·토큰 검증 기능은 같고, 코드는 평소 `Filter`·`SecurityFilterChain`을 그대로 쓴다. `Mono`·`Flux`를 쓰지 않는다. 설정 키는 `spring.cloud.gateway.server.webmvc.*`다 (`server.webflux`가 아니다).

## 5. dev 환경에서 실행하기

> 라우팅·토큰 검증이 들어 있다. DB는 없지만 `COGNITO_ISSUER_URI`·`COGNITO_CLIENT_ID`가 없으면 뜨지 않는다. 뒤 서비스는 dev 클러스터에 있으므로 PC에서 띄우면 라우팅 대상(`http://member-service:8080` 등)에 닿지 않는다. 확인은 dev 클러스터에서 한다.

개발할 때도 AWS dev 환경의 RDS·SQS·Cognito를 그대로 쓴다. AWS 로그인, DB 포트 포워딩(SSM), 개인 큐 설정은 [개발 환경 접속 가이드](https://github.com/sesac-freshlink/.github/blob/main/docs/개발환경-접속.md)를 따른다.

```bash
export SPRING_PROFILES_ACTIVE=dev
export COGNITO_ISSUER_URI=https://cognito-idp.ap-northeast-2.amazonaws.com/<dev UserPoolId>
export COGNITO_CLIENT_ID=<dev 앱 클라이언트 ID>
./gradlew bootRun
```

뒤의 서비스는 dev 클러스터에 배포된 것을 쓴다. 토큰은 프론트에서 로그인하거나 `aws cognito-idp initiate-auth`로 받는다.

## 6. 브랜치와 기여

- `main`(배포) ← `dev`(통합, 기본 브랜치) ← `feat/<기능ID>-<설명>` (예: `feat/C-03-role-filter`)
- 커밋·PR 제목: `feat(C-03): 점주 경로 역할 확인`
- PR·승인은 필수가 아니다. 담당자가 `dev`·`main`에 직접 push해도 된다. PR을 쓸 때는 작업 브랜치 → `dev`는 Squash merge, `dev` → `main`은 Merge commit.

자세한 규칙은 [.github 레포](https://github.com/sesac-freshlink/.github)를 참고한다.
