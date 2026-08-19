# practice-transaction (bank-transfer)

트랜잭션 & 동시성 실전 학습 프로젝트. 단순 구현이 아니라 **버그를 먼저 재현하고 고치는 방식**으로 진행한다.

## 스택
- Java 21
- Spring Boot 4.1.0 (jakarta.* 네임스페이스)
- Spring Data JPA + Hibernate (Spring Boot BOM 관리 버전)
- PostgreSQL (Docker Compose로 수동 기동)
- Gradle
- 테스트: Testcontainers (자동화 테스트는 실제 PostgreSQL 컨테이너로 격리)

## 아키텍처
`io.hkarling.transaction` 하위 라이트 3+1계층 (포트/어댑터 추상화 없음 — 실습 목적상 오버헤드로 판단해 제외, 자세한 배경은 [`docs/LOG000-project-setup.md`](docs/LOG000-project-setup.md) 참고):
- `common` — 계층 간 공유 유틸리티, 공통 예외 타입
- `domain` — JPA 엔티티(도메인 규칙 포함), 도메인 서비스
- `app` — 유스케이스/애플리케이션 서비스, `@Transactional` 경계
- `infra` — Spring Data JPA 리포지토리, 설정 클래스, 외부 연동

## 진행 규칙
각 챕터는 반드시 이 순서로 진행한다:
1. 버그 있는 코드 작성
2. 테스트로 버그 재현
3. 수정
4. `docs/LOG###-부제.md`에 로그 + ADR(Decision/Drivers/Alternatives/Consequences/Follow-ups) 작성

챕터 시작 전 목표와 예상 문제점을 먼저 설명한다.

## 문서 구조
서브디렉터리(`docs/adr/`, `docs/log/`, `docs/plans/`) 없이 `docs/` 바로 아래 평평하게 쌓는다:
- `docs/LOG###-부제.md` — 각 단계(세팅/챕터)의 진행 로그: 시행착오, Q&A, 최종 구성, 최종 결정(ADR 섹션)까지 한 문서에 통합

## 진행 상황

### Phase 0 — 세팅
- [x] 프로젝트 초기 세팅 (`build.gradle`, `compose.yaml`, `application.yaml`, 패키지 스켈레톤, Testcontainers) — [`docs/LOG000-project-setup.md`](docs/LOG000-project-setup.md)

### Phase 1 — 단일 DB 트랜잭션 기초
- [x] 1. `@Transactional` 없는 기본 이체 구현 → 뭐가 터지는지 확인 — [`docs/LOG001-chapter1.md`](docs/LOG001-chapter1.md)
- [x] 2. 격리 수준별 실험 (READ_UNCOMMITTED → SERIALIZABLE) — [`docs/LOG002-chapter2.md`](docs/LOG002-chapter2.md)
- [x] 3. 전파 속성 실험 (REQUIRED / REQUIRES_NEW / NESTED) — [`docs/LOG003-chapter3.md`](docs/LOG003-chapter3.md)
- [x] 4. 동시성 문제 재현 — 잔액 음수, Lost Update — [`docs/LOG004-chapter4.md`](docs/LOG004-chapter4.md)
- [x] 5. 낙관적 락 (`@Version`) + 재시도 전략 — [`docs/LOG005-chapter5.md`](docs/LOG005-chapter5.md)
- [x] 6. 비관적 락 (`SELECT FOR UPDATE`) + 데드락 재현 및 해결 — [`docs/LOG006-chapter6.md`](docs/LOG006-chapter6.md)

### Phase 2 — 복잡한 비즈니스 케이스
- [x] 7. 이체 한도 / 일별 한도 동시성 제어 — [`docs/LOG007-chapter7.md`](docs/LOG007-chapter7.md)
- [x] 8. 수수료 트랜잭션 — 부분 실패 시 롤백 범위 — [`docs/LOG008-chapter8.md`](docs/LOG008-chapter8.md)
- [x] 9. 감사 로그 — 트랜잭션 실패해도 로그는 남아야 함 (`REQUIRES_NEW` 실전) — [`docs/LOG009-chapter9.md`](docs/LOG009-chapter9.md)
- [x] 10. 이벤트 발행 타이밍 (`@TransactionalEventListener`) — [`docs/LOG010-chapter10.md`](docs/LOG010-chapter10.md)

### Phase 3 — 분산 트랜잭션
- [x] 11. 2PC 이론과 한계 — [`docs/LOG011-chapter11.md`](docs/LOG011-chapter11.md)
- [x] 12. SAGA Choreography — [`docs/LOG012-chapter12.md`](docs/LOG012-chapter12.md)
- [x] 13. SAGA Orchestration — [`docs/LOG013-chapter13.md`](docs/LOG013-chapter13.md)
- [x] 14. Outbox 패턴 — [`docs/LOG014-chapter14.md`](docs/LOG014-chapter14.md)
- [x] 15. 멱등성 설계 — [`docs/LOG015-chapter15.md`](docs/LOG015-chapter15.md)

### Phase 4 — 운영 관점
- [x] 16. 락 경합 모니터링 — [`docs/LOG016-chapter16.md`](docs/LOG016-chapter16.md)
- [x] 17. 커넥션 풀 고갈 시뮬레이션 (HikariCP) — [`docs/LOG017-chapter17.md`](docs/LOG017-chapter17.md)
- [ ] 18. 동시성 테스트 자동화

## 로컬 실행
```bash
docker compose up -d          # PostgreSQL 기동 (고정 포트 15432)
./gradlew bootRun             # 앱 실행
./gradlew test                # Testcontainers로 격리된 테스트 실행 (compose 안 떠 있어도 됨)
```
