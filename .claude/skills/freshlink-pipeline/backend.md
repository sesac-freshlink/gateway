# 백엔드 (gateway, member-service, inventory-service, order-service, notification-service, logistics-simulator, common)

`freshlink-pipeline` 1단계에서 백엔드 레포를 고칠 때만 읽는다. 레포 `CLAUDE.md`와 다르면 레포 쪽이 우선이다.

## 팀 결정 (바꾸지 않는다)

| 주제 | 결정 |
| --- | --- |
| 스택 | Java 21, Spring Boot 4.0.x, Spring Cloud 2025.1.x, Spring Cloud AWS 4.x, Jackson 3, Gradle 9, 프로필 `dev`·`prod`, 포트 8080 하나 |
| 인증 | Amazon Cognito + gateway BFF. gateway가 `/api/auth/**`로 Cognito를 대신 부르고 ID 토큰을 HttpOnly 쿠키 `fl_id`로 둔다. gateway가 JWKS로 RS256·`iss`·`exp`·`token_use = id`·`aud` 검증. 역할 = `cognito:groups`(OWNER/CUSTOMER/ADMIN), 점포 = `custom:storeId`. 기준 문서 `.github` `docs/인수인계/BFF.md` |
| 신원 전달 | gateway가 위조 헤더를 지우고 `X-User-Id`(sub, UUID 문자열)·`X-User-Role`·`X-Store-Id`를 붙인다. 서비스는 토큰을 다시 검증하지 않는다. 소유권 확인은 서비스 몫 |
| 재고 변경 | 전부 `inventory-commands.fifo`(그룹 = storeId) 경유. Outbox(폴링) + `processed_message` 멱등 (common) |
| 쪽지 재시도 | 재시도·DLQ 재처리는 반드시 한다(`.github` `docs/규칙/백엔드-공통-규칙.md` 7장) |
| 저장소 | 서비스마다 RDS MySQL + Flyway. 주문과 outbox를 한 트랜잭션에 넣는다. Valkey(ElastiCache)는 order-service만(선착순) |
| 알림 | SQS 알림 큐 → notification-service → SSE |
| 실시간 재고 | 점주 화면 3초 폴링 `GET /owner/inventory`. 고객 목록은 폴링하지 않는다 |
| 배포 | GitHub Actions(`.github` 재사용 워크플로) → ECR → gitops 이미지 태그 커밋 → ArgoCD → EKS. 백엔드 작업에서 gitops·infra는 고치지 않고 "인프라 쪽 할 일"로 보고한다 |
| 관측성 | Actuator·Micrometer → Prometheus(앱 포트 `/actuator/prometheus`, `management.server.port` 없음), Micrometer Tracing → Zipkin, JSON 로그(traceId) → Loki. 서비스끼리는 `http://<레포>:8080`. gateway는 외부 `/actuator` 차단 |
| common | 바꾸면 버전을 올리고 태그 배포. 서비스에서 시험할 때는 `-SNAPSHOT`을 GitHub Packages에 올린다 |

## 검증

| 게이트 | 명령 / 확인 |
| --- | --- |
| 빌드·테스트 | `./gradlew build` (Windows `gradlew.bat build`). 실패 시 실패한 테스트 이름과 첫 스택만 본다 |
| 정적 검사 | `build.gradle`에 설정된 것만 (`spotlessCheck`, `checkstyleMain` 등). 없으면 건너뛴다 |
| 비밀값 | 바뀐 파일에서 `AKIA`, `secret`, `password:` 뒤 실값, 토스 `test_sk_`/`live_sk_`, JWT 원문 검색 |
| 금지 패턴 | gateway 밖에서 JWT 파싱(`Jwts`, `JwtDecoder`), inventory 밖에서 재고 표 직접 변경, `@Scheduled`에 `@SchedulerLock` 없음, 엔티티를 응답으로 그대로 반환 |

- 외부 접속 테스트(실제 AWS, Cognito, 토스)는 돌리지 않는다. Testcontainers(MySQL)는 Docker가 있을 때만. SQS는 목으로.
- 작은 일이면 바뀐 레포의 `./gradlew build`와 비밀값 검색만 한다.

## 리뷰 점검표 (위에서부터)

1. **인증·권한** — gateway: `aud`·`token_use` 검사 누락, `/api/owner/**` OWNER 확인 누락, 클라이언트가 보낸 `X-User-*`·`X-Store-Id`를 지우지 않음, 공개 경로가 너무 넓음, 토큰·쿠키 원문 로그. 서비스: 토큰 재검증, 점주 API가 점포 ID를 경로·바디로 받음, 소유권 확인 누락, `application.yml` 기본값에 실값
2. **재고 정합성** — 쪽지를 거치지 않은 재고 변경, 메시지 그룹이 `storeId`가 아님, 리스너 멱등 누락, 비즈니스 변경과 outbox가 다른 트랜잭션, 유통기한 지난 lot이 판매·예약 가능 수량에 포함, FEFO 순서, 예약 수량 음수, 날짜 경계(Asia/Seoul), Saga 보상(RELEASE) 누락, 만료 스케줄러와 결제 승인 경합, 원장(ledger) 합계와 lot 수량 불일치
3. **API·데이터 계약** — README API 표와 다름, 프론트(`api.ts`·MSW)에 알릴 응답 변경, 쪽지 형식 변경인데 common 버전·`message-spec` 라벨 없음(`/message-check`), 사용자 ID 칸이 `VARCHAR(36)` 아님, 다른 서비스 표 직접 조회, 유니크 제약 누락
4. **운영** — ShedLock 없음(파드 2개), Actuator·tracing 누락, 로그에 traceId 없음, 환경 변수 이름이 gitops values와 다름, N+1, 외부 호출(토스·Bedrock) 타임아웃 없음
5. **테스트** — 수용 기준을 덮는지, 의미 없는 테스트

`/api-table`로 README API 표와 컨트롤러를 맞춰 볼 수 있다.

## 마지막 보고에 넣을 것

- 합치는 순서 (common → 서비스 → gateway)
- 다른 담당자에게 알릴 것 (남의 레포 PR, 쪽지 형식 변경)
- 인프라 쪽 할 일 (환경 변수, 큐, IAM)
- 프론트 영향 (응답 필드가 바뀌면 frontend `api.ts`·MSW)
