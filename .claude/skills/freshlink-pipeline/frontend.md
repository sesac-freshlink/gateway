# 프론트 (frontend)

`freshlink-pipeline` 1단계에서 frontend를 고칠 때만 읽는다. 스택·폴더·금지 사항(red flags)은 frontend `CLAUDE.md`가 기준이다.

## 검증

| 게이트 | 명령 |
| --- | --- |
| 타입 | `npx tsc -b` |
| lint | `npm run lint` (oxlint) |
| 포맷 | `npm run format:check` (실패하면 `npx prettier --write <파일>`로 고쳐도 된다) |
| 테스트 | `npm test` (vitest run) |
| 빌드 | `npm run build` — 라우트·환경 변수·MSW 설정을 바꿨을 때 |

- 작은 일이면 `npx tsc -b`와 `npm run lint`만 한다.
- 화면을 바꿨으면 `npm run dev`(MSW + 가짜 로그인: `owner`로 시작하는 이메일 = 점주, `admin` = 관리자, 나머지 = 고객)로 열어 볼 수 있으면 본다. 점주는 넓은 화면, 고객은 약 390px. 띄운 dev 서버는 내가 띄운 프로세스만 끈다. 브라우저로 확인하지 못했으면 "화면 확인 못 함"이라고 적는다. 타입·lint 통과만으로 동작한다고 말하지 않는다.
- 진짜 로그인(쿠키·BFF)은 dev 배포 주소에서 확인한다. PC에 gateway를 띄우지 않는다.

## 리뷰 점검표 (위에서부터)

1. frontend `CLAUDE.md`의 red flags — `any`·근거 없는 `as`·`!`, 서버 데이터를 `useState`/Zustand에 복사, 화면이 axios를 직접 호출(api.ts 훅 우회), `/api` 중복 경로, 폴링 주기 하드코딩(`POLL_MS`), mutation 뒤 invalidate 누락, queryKey 파라미터 누락, 화면 안에서 role 분기로 권한 차단(RequireRole 우회), `react-router-dom` import, `cn()` 없이 className 조립, `shared/ui` 중복 구현, 라우트를 추가했는데 README 3장·메뉴 미반영, MSW 응답이 `api.ts` 타입과 다름
2. 정확성 — `data?.` 누락, 로딩·오류·빈 상태, dayjs 날짜 경계(유통기한 당일·시간대), 폼 검증, 이중 제출(결제·폐기 버튼 `isPending`)
3. 테스트 — 수용 기준을 덮는지. 대상 옆 `{대상}.test.ts(x)`, `describe`/`it` 설명은 한국어

## 마지막 보고에 넣을 것

- 백엔드 영향 (API가 아직 없으면 MSW로만 만든 것)
- 다른 사람 화면에 영향이 가는 공통 변경이면 팀 채널에 알릴 것
