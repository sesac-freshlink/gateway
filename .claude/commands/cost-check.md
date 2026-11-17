---
description: 현재 변경분이 월 AWS 비용에 주는 영향을 추정한다
argument-hint: "[비교 기준 브랜치 (기본 main)]"
---

현재 브랜치의 인프라 변경분(`git diff ${ARGUMENTS:-main}...HEAD`, infra·gitops)이 월 AWS 비용에 주는 영향을 추정한다. AWS에 접속하지 않는다.

1. 변경분에서 과금 리소스를 찾는다: EKS 노드(인스턴스 타입·desired/max), NAT Gateway, RDS(클래스·스토리지), ALB, 인터페이스 VPC 엔드포인트, EBS(PVC 크기), S3·CloudFront, SQS, Cognito, CloudWatch 로그.
2. 운영 시간은 `.github` 레포 `docs/기획/aws-리소스-계획.md` 6-5 일정 기준이다(dev: 평일 09:00~21:00, 주말 `cluster` 삭제 / prod: 2026-11-05부터 주말 포함 매일 09:00~21:00, 일요일 `cluster` 삭제). 영향 환경(`global`/dev/prod)별로 나눠 계산한다. 시간당 단가는 ap-northeast-2 온디맨드 기준이며, 확실하지 않은 단가는 "추정"으로 표시한다.
3. 출력: 환경별 리소스 변경 전/후 월 비용 표, 합계 변화, `aws-리소스-계획.md` 6-6 dev·prod 합계(약 310~340 USD, 10/19~11/20)와 Budgets(10·11월 합계 360 USD) 대비 여유. 비용을 줄일 수 있는 대안이 있으면 한 줄씩.
