---
description: 컨트롤러 코드에서 API 목록을 뽑아 README API 표·gateway 라우팅과 비교한다
argument-hint: "[레포 폴더 (생략하면 지금 레포)]"
---

`${ARGUMENTS:-.}` 레포의 컨트롤러(`@RestController`, `@*Mapping`)를 읽어 실제 API 목록을 만들고 문서와 비교한다. 코드를 고치지 않는다. 서버를 띄우지 않는다.

1. 실제 API 표: 메서드, 서비스 안 경로, gateway 경유 경로(`/api` + 경로), 받는 헤더(`X-User-Id`·`X-User-Role`·`X-Store-Id`), 요청 DTO, 응답 DTO, 기능 ID(주석·README에서).
2. 비교 결과 (불일치만):
   - README "기능과 API" / "API" 표에 있는데 코드에 없음, 코드에 있는데 README에 없음, 경로·메서드 다름
   - gateway README 라우팅 표(옆에 `gateway` 클론이 있으면 그 README와 라우트 설정)에 이 경로가 빠졌거나 다른 서비스로 감
   - 점주 API가 점포 ID를 경로로 받음 (`X-Store-Id`를 써야 한다)
   - 공개 경로(점포 검색, 점포 상품 목록)가 아닌데 헤더를 안 받음
3. 프론트 영향: 응답 필드가 README와 다르면 frontend `api.ts`·MSW 핸들러를 고쳐야 한다고 한 줄씩.

출력은 PR 본문이나 README에 붙일 수 있는 Markdown 표 (평이한 한국어).
