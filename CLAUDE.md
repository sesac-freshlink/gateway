# gateway - AI 작업 가이드

> 모든 `/api/*` 요청의 입구. Spring Cloud Gateway로 라우팅하고 Cognito ID 토큰을 검증한 뒤 사용자 헤더를 붙여 서비스로 넘긴다. 담당: 김진아. 포트 8080.

---

## 문서 참조

| 문서 | 내용 |
|------|------|
| `README.md` | 이 레포의 기능·API·데이터 모델·폴더 구조·로컬 실행 (진실) |
| `.github` 레포 `docs/기획안-및-설계도.md` | 기능 ID, 메시지 명세, 데이터 모델 |
| `.github` 레포 `CONTRIBUTING.md` | 브랜치·커밋·PR 규칙 |
| `.github` 레포 `docs/ai-agents.md` | 백엔드 에이전트 플러그인(`freshlink-backend`) 설치·사용법 (사람용) |

이 `CLAUDE.md`는 AI 작업 규칙만 담는다. 위 문서와 겹치는 사실은 위 문서가 진실.

## 에이전트

백엔드 에이전트 7개와 작업 순서는 `freshlink-backend` 플러그인에 있다 (`.github` 레포에서 배포). 새 기능 요청이면 `backend-pipeline` 스킬을 먼저 읽고 그 순서대로 서브에이전트를 쓴다. 플러그인이 설치되어 있지 않으면 사용자에게 `.github` 레포 `docs/ai-agents.md`의 설치 명령을 안내한다.

서브에이전트 산출물(`_workspace/*.md`)은 짧게(표·불릿). 레포에 커밋되는 것(README, 코드 주석, PR 본문)은 평이한 한국어 문장(`~한다` 체).

---

## 공통 규칙

```
⛔ 담당자가 아닌 사람의 요청이면 main·dev에 직접 push 금지 → feat/<기능ID>-<설명> 브랜치 + dev 대상 PR
⛔ git push --force, git reset --hard, git clean, 브랜치 삭제 금지
⛔ DB DROP / TRUNCATE / 조건 없는 DELETE, docker compose down -v 금지
⛔ 비밀값(DB 비밀번호, 토스 키, AWS 키, 토큰 원문) 커밋·출력 금지. 설정은 ${ENV}로
⛔ 커밋·push·PR은 사용자가 요청할 때만
✅ Java 21, Spring Boot 3, Gradle(./gradlew). 아직 뼈대면 README "로컬에서 실행하기" 안내대로 프로젝트를 만든다
✅ 인증: gateway가 Cognito ID 토큰을 검증하고 X-User-Id(sub, UUID 문자열)·X-User-Role·X-Store-Id 헤더로 넘긴다. 서비스는 토큰을 다시 검증하지 않는다
✅ 실시간 재고: 점주 화면이 3초마다 GET /owner/inventory를 조회한다 (폴링)
✅ 주문 저장소 DynamoDB 도입은 검토 중, 미정. 어느 쪽도 가정하지 말고 사용자에게 묻는다
✅ 관측성(서비스·gateway): Actuator(관리 포트 8081, health·prometheus), Micrometer Tracing → Zipkin, JSON 로그(traceId)
✅ 배포 설정(gitops values, IRSA, 큐)은 이 레포에서 고치지 않는다 → freshlink-infra 담당에게 알린다
```

---

## 이 레포 규칙

```
✅ WebFlux(리액티브) 기반. Servlet API(HttpServletRequest, OncePerRequestFilter) 쓰지 않는다
✅ 토큰 검증: issuer-uri(${COGNITO_ISSUER_URI})로 JWKS, RS256·exp·iss + token_use = id + aud = ${COGNITO_CLIENT_ID}. 공유 비밀 키 금지
✅ /api/owner/** 는 cognito:groups에 OWNER 필요 (없으면 403, 토큰 문제는 401)
✅ 공개 경로는 점포 검색·점포 상품 목록만 (README 3장). 넓히지 않는다
✅ 클라이언트가 보낸 X-User-Id·X-User-Role·X-Store-Id는 먼저 지우고 토큰 값으로 다시 채운다
✅ 라우팅은 README 라우팅 표 기준, /api 접두사를 떼고 넘긴다. 소유권 확인은 서비스 몫
⛔ 토큰 원문·Authorization 헤더를 로그에 남기지 않는다
```

라우트·헤더 이름·그룹 이름을 바꾸면 frontend(`claims.ts`, `api.ts`), 서비스 레포, infra `modules/cognito`가 같이 바뀌어야 한다. 작업 전 사용자에게 알린다.

## 검증

```bash
./gradlew build    # 컴파일 + 테스트 (Windows: gradlew.bat build)
```

테스트에서 반드시 확인: 토큰 없음 401, aud 다름 401, CUSTOMER로 /api/owner/** 403, 위조 X-User-Id 제거, 공개 경로 통과.
