---
description: 현재 변경분이 SQS 쪽지 형식(common DTO)을 바꾸는지 확인하고 알릴 사람·라벨·버전을 정리한다
argument-hint: "[비교 기준 브랜치 (기본 origin/dev)]"
---

현재 브랜치 변경분(`git diff ${ARGUMENTS:-origin/dev}...HEAD`)이 쪽지(SQS 메시지) 형식에 주는 영향을 확인한다. 코드를 고치지 않는다. 큐에 접속하지 않는다.

1. 변경분에서 쪽지 관련 파일을 찾는다: common `message/` DTO, `outbox/`, 서비스의 리스너(`@SqsListener`)·발행 코드, 큐 이름·메시지 그룹·중복 제거 키 설정.
2. 쪽지 type별 표 (INBOUND, RESERVE, CONFIRM, RELEASE, DISPOSE, RESERVED, RESERVE_FAILED): 바뀐 필드, 변경 종류(추가 / 삭제 / 이름·타입 변경), 호환 여부, 보내는 쪽·받는 쪽 레포.
3. 확인할 것:
   - 호환이 깨지는 변경이면 common 버전을 올렸는지 (`build.gradle` `version`), 받는 쪽 서비스가 같은 버전을 쓰는지
   - FIFO 큐 메시지 그룹이 `storeId`, 중복 제거 키가 `messageId`인지
   - 받는 쪽 리스너가 `processed_message`로 멱등 처리하는지
   - `.github` 레포 `docs/기획/기획안-및-설계도.md` 메시지 명세와 다른 점
4. 마지막에 체크리스트: PR에 `message-spec` 라벨, 합치기 전 이태우(inventory)·김경우(order)에게 알림, common 태그 배포 순서(common → 서비스).

출력은 PR 본문에 붙일 수 있는 Markdown (평이한 한국어). 쪽지 변경이 없으면 "쪽지 형식 변경 없음" 한 줄.
