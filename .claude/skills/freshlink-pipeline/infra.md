# 인프라 (infra, gitops, GitHub Actions 워크플로)

`freshlink-pipeline` 1단계에서 인프라 레포를 고칠 때만 읽는다. 사실의 원천은 `.github` 레포 `docs/기획/aws-리소스-계획.md`와 `gitops/README.md`다. 이 파일과 다르면 그 둘을 따르고 사용자에게 알린다.

## 구조 (dev·prod)

| 항목 | 내용 |
| --- | --- |
| infra 폴더 | `global/`, `envs/{dev,prod}/{persistent,cluster}/`, `modules/` |
| state 키 | `global`, `dev/persistent`, `dev/cluster`, `prod/persistent`, `prod/cluster` |
| `global` | 두 환경 공용: ECR, GitHub OIDC 공급자, state 저장소, Budgets |
| `persistent` | 끄는 동안에도 남는 것: VPC, RDS, SSM 접속용 nano, Secrets Manager, S3·CloudFront, SQS, IAM, EventBridge Scheduler, Loki 버킷, Cognito·Lambda, Valkey |
| `cluster` | 지웠다 다시 만드는 것: EKS, 노드 그룹, NAT, Helm(ArgoCD, LB Controller, metrics-server, External Secrets) |
| 의존 방향 | `cluster` → 같은 환경 `persistent`, 각 환경 → `global`. dev·prod는 서로 참조하지 않는다 |
| 모듈 | dev·prod가 같은 모듈을 쓰고 변수 파일만 다르다. `if prod` 분기 금지 |
| 이름 | `freshlink-<env>-<이름>`, 큐 `<env>-…`, ECR·Helm 릴리스·Deployment는 레포 이름 그대로 |
| gitops | `envs/{dev,prod}/<svc>/values.yaml`(키 구조 같고 값만 다름), `argocd/{dev,prod}/`. `main`만 |
| 배포 | 서비스 `dev` push → 빌드 → ECR(커밋 7자리 태그) → gitops `envs/dev` 커밋. `main` 머지 → 같은 태그를 `envs/prod`로 승격(다시 빌드하지 않음) |
| 켜고 끄기 | dev 평일 09:00~21:00. prod는 2026-11-13부터 데모(11/20)까지 클러스터를 지우지 않는다. 자세한 일정은 `aws-리소스-계획.md` 6-5 |
| 권한 | 팀원 IAM 그룹 `freshlink-developers`는 dev만. `dev` 브랜치가 맡는 OIDC Role에 prod 쓰기 권한 금지 |
| 도메인 | dev `https://dev.getfreshlink.com`, prod `https://getfreshlink.com` |
| AWS 프로필 | Terraform은 `AWS_PROFILE=freshlink-admin` |

정하지 않은 값은 지어내지 않고 `.github` `docs/할일/김진아-할일-목록.md`를 본다.

## 검증

| 레포 | 게이트 |
| --- | --- |
| infra | `terraform fmt -check -recursive` / 바뀐 루트마다 `terraform -chdir=<루트> init -backend=false` + `validate` (모듈을 바꿨으면 그 모듈을 부르는 dev·prod 루트 모두) / 있으면 `tflint --recursive`, `trivy config .` |
| gitops | `helm lint charts/spring-service` / 바뀐 서비스·환경마다 `helm template … -f envs/<env>/<svc>/values.yaml \| kubeconform -strict -ignore-missing-schemas` / dev·prod values 키 비교 / 이미지 태그가 `latest`·빈 값이 아닌지 |
| workflows | `actionlint` |

- `terraform plan`은 AWS 계정을 읽으므로 사용자 확인 뒤에만 돌린다. 돌렸으면 `/plan-summary`로 PR 본문용 요약을 만든다. 안 돌렸으면 PR에 "plan 미실행"이라고 적는다.
- `terraform fmt` 같은 포맷 수정은 해도 된다.

## 리뷰 점검표 (위에서부터)

1. **보안** — 비밀값·tfvars 실값·state 커밋, IAM `"*"`, IRSA trust에 `sub` 조건 누락, 보안 그룹 `0.0.0.0/0`(ALB 80/443 제외), RDS 공개, S3 퍼블릭, Actions 장기 키·`permissions` 과다·`pull_request_target`·서드파티 액션 버전 미고정, Cognito 콜백 와일드카드, 환경 경계(dev Role이 prod 리소스 접근, 팀원 그룹에 prod 권한)
2. **끄고 켜기 안전성** — `persistent`가 `cluster`에 의존, 환경 간 참조, destroy를 막는 것(LB Controller가 만든 ALB·ENI·PVC·finalizer, `cluster` 안의 `prevent_destroy`), 재생성 뒤 사람 손이 필요한 단계, 스케줄과 이름·태그 불일치
3. **비용** — 인스턴스 타입·노드 수·NAT·RDS 크기, 새 시간당 과금 리소스, prod를 상시 켜는 변경. `/cost-check`로 추정
4. **GitOps·CI** — `latest` 태그, resources·probe 누락, 차트에 서비스 하나 때문에 분기, CI가 클러스터에 접근, dev·prod values 키 불일치, prod 태그를 다시 빌드
5. **정확성** — 변수 기본값, 출력값 누락, cron(UTC ↔ 한국 시간) 변환

## 마지막 보고에 넣을 것

- 사람이 할 일: PR 합치기, 합친 뒤 `terraform apply`(루트와 순서 `global` → `persistent` → `cluster`, dev 먼저), 그 밖의 수동 작업
- 비용 영향
