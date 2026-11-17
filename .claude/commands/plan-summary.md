---
description: terraform plan 결과를 PR 본문에 붙일 표로 요약한다
argument-hint: "[plan 출력 파일 경로 (생략하면 대화에 붙여넣은 출력)]"
---

`terraform plan` 출력을 PR 본문 "확인 방법"에 붙일 수 있게 요약한다. plan을 새로 실행하지 않는다.

입력: `$ARGUMENTS` 경로의 파일. 없으면 사용자가 대화에 붙여넣은 출력.

출력 (PR 본문에 그대로 붙일 Markdown, 평이한 한국어):

1. 한 줄 요약: `Plan: N to add, N to change, N to destroy` 와 루트(`global` / `envs/<env>/persistent` / `envs/<env>/cluster`)·state 키
2. **삭제·교체(replace) 리소스 표** — 맨 위에 둔다. 없으면 "삭제·교체 없음"
3. 생성 / 변경 리소스 표 (주소, 바뀌는 주요 속성)
4. 주의할 점: 비용이 드는 리소스, 보안 그룹·IAM 변경, 주말 destroy에 영향 주는 것, 다른 환경 리소스가 plan에 보이는 것(예: dev 변경에 prod 리소스)
5. 원본 plan은 `<details>`로 접어서 붙인다. 비밀값으로 보이는 값(`sensitive`, 토큰, 비밀번호)은 `***`로 가린다.
