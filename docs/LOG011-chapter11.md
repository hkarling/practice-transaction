# LOG011 — 챕터 11: 2PC 이론과 한계

전체 진행도: [`README.md`](../README.md)

## 성격 — 이론 중심 챕터
이 챕터는 코드가 없다. 프로젝트가 PostgreSQL 하나만 써서, 진짜 2PC(XA 트랜잭션)를 시연하려면 두 번째 리소스(다른 DB/메시지 큐)와 JTA 트랜잭션 매니저(Atomikos 등) 같은 새 의존성이 필요한데, 이건 다음 챕터들(SAGA, Outbox)이 "왜 2PC 대신 이 방법을 쓰는지" 이해하기 위한 배경 지식으로 필요한 정도라 실습 인프라를 새로 안 늘리고 개념 설명으로 마무리하기로 함.

## 2PC(Two-Phase Commit)란
여러 독립된 리소스(서로 다른 DB, 메시지 큐 등)에 걸친 작업을 하나의 원자적 트랜잭션으로 묶는 프로토콜. 코디네이터(Coordinator) 하나 + 참가자(Participant, 각 리소스 매니저) 여럿으로 구성.

**Phase 1 — Prepare(투표)**: 코디네이터가 모든 참가자에게 "커밋할 수 있어?"라고 물음. 각 참가자는 실제 커밋은 안 하고 커밋을 보장할 수 있는 상태까지만 준비(락 확보, durable 로그 기록) 후 yes/no 응답.

**Phase 2 — Commit/Abort(확정)**: 전원 yes면 전원에게 commit, 한 명이라도 no/무응답이면 전원에게 abort. 그제서야 참가자들이 실제 커밋/롤백하고 락 해제.

## 한계
1. **블로킹 문제(가장 치명적)** — 참가자가 yes 투표 후(락 잡고 대기 중) 코디네이터가 죽으면, 그 참가자는 커밋/롤백 여부를 스스로 판단 못 하고 코디네이터가 살아날 때까지 **락을 쥔 채 무한정 대기**해야 함. 챕터 6("락은 최대한 짧게 잡아라")과 정반대 상황이 구조적으로 강제됨.
2. **코디네이터 단일 장애점(SPOF)** — 코디네이터가 죽으면 전체 트랜잭션이 멈춤.
3. **동기·블로킹 성격** — 가장 느린 참가자/네트워크 지연에 전체 처리 속도가 좌우됨. 처리량·확장성에 불리.
4. **이기종 시스템 간 적용 어려움** — 마이크로서비스(Database per Service)에서 서비스 경계를 넘어 2PC를 걸려면 모든 참여 시스템이 XA 프로토콜을 지원해야 하는데, Kafka 등 메시지 큐나 외부 API 대부분은 XA 미지원.

## 참고 — XA 프로토콜
X/Open(현재 The Open Group)이 1991년경 정의한 분산 트랜잭션 처리 표준. 2PC를 "어떻게 구현하는가"에 대한 구체적인 통신 인터페이스 스펙 — 이 인터페이스를 구현하면 트랜잭션 매니저(코디네이터)와 대화할 수 있고 2PC에 참여할 수 있다는 계약.

**구조**: 트랜잭션 매니저(TM, 코디네이터) + 리소스 매니저(RM, DB/메시지 큐 등 참가자). 하나의 글로벌 트랜잭션은 고유 `Xid`로 식별되고, 여러 리소스가 같은 `Xid`로 "가입(enlist)"해서 하나의 트랜잭션에 묶인다.

**`XAResource` 핵심 메서드** (`javax.transaction.xa.XAResource`):
- `start(Xid, flags)` — 트랜잭션 작업 시작
- `end(Xid, flags)` — 작업 종료 알림 (아직 커밋 아님)
- `prepare(Xid)` — Phase 1, "커밋 준비됐어?" 응답(`XA_OK`)
- `commit(Xid, onePhase)` / `rollback(Xid)` — Phase 2 확정
- `recover(flags)` — 장애 복구 시 prepare는 됐지만 최종 결정을 못 받고 매달려있는 트랜잭션 조회 (블로킹 문제 대응용)

**실제로 쓰려면**: JDBC의 일반 `DataSource` 대신 `XADataSource`를 구현한 드라이버 필요(PostgreSQL은 `PGXADataSource`). Java/Jakarta EE에서는 JTA(Java Transaction API)가 XA 위에 얹힌 표준 API(`UserTransaction`, `TransactionManager`). Spring에서 쓰려면 `JtaTransactionManager` + Atomikos/Bitronix/Narayana 같은 임베디드 트랜잭션 매니저 추가 필요 — 이번 챕터에서 새 의존성 없이 개념으로만 다루기로 한 이유.

**한계로 이어지는 지점**: Kafka, 대부분의 REST API, NoSQL 저장소 등은 `XAResource`를 구현하지 않아 애초에 XA에 낄 수 없음. 마이크로서비스에서 서비스마다 다른 저장소/메시징을 쓰는 게 흔한데 전부가 XA를 지원하는 경우는 드물어 2PC 자체를 적용하기 어려움 — 위 "이기종 시스템 간 적용 어려움"의 실제 원인이자 SAGA/Outbox로 넘어가는 실질적 이유.

## 왜 SAGA/Outbox로 넘어가는가
2PC는 강한 원자성을 보장하는 대신 블로킹·결합도·확장성에서 대가를 치른다. 마이크로서비스 시대엔 이 대가가 너무 커서:
- **SAGA**(챕터 12-13): 원자성을 포기하고 "보상 트랜잭션"으로 최종 일관성(eventual consistency)을 얻음.
- **Outbox**(챕터 14): 로컬 트랜잭션 안에서 이벤트 발행을 원자적으로 보장 — 챕터 10의 `AFTER_COMMIT` 방식보다 더 견고한 대안.

## 완료 체크리스트
- [x] 2PC의 2단계(Prepare/Commit) 구조 이해
- [x] 블로킹 문제 등 4가지 한계 정리
- [x] SAGA/Outbox로 이어지는 이유 정리
- [x] 이 로그 문서 작성 (코드 변경 없음)

## ADR

**Decision**
- 2PC는 이 프로젝트에서 실제로 구현하지 않는다 — 개념 학습으로 충분하다고 판단.

**Drivers**
- 진짜 XA 데모를 하려면 두 번째 리소스 매니저 + JTA 트랜잭션 매니저(Atomikos/Bitronix 등) 의존성이 필요한데, 이후 챕터(SAGA/Outbox)의 실습에는 직접 필요하지 않음.
- 이 프로젝트의 커리큘럼 목적(트랜잭션/동시성 실전 학습)에서 2PC의 실무적 가치는 "왜 안 쓰는지 이해하기"에 있지, 직접 구현해보는 데 있지 않음.

**Alternatives considered**
- Atomikos/Bitronix로 실제 XA 2PC 데모 구현 — 기각: 새 의존성 + 두 번째 리소스 매니저가 필요한 큰 인프라 작업 대비, 이 챕터의 학습 목표(한계 이해) 대비 비용이 큼.

**Consequences**
- 다음 챕터(SAGA)부터 다시 코드 작성 재개.

**Follow-ups**
- 챕터 12-13(SAGA)에서 2PC 대신 어떻게 원자성 없이 일관성을 얻는지 직접 구현.
- 챕터 14(Outbox)에서 챕터 10의 `AFTER_COMMIT` 방식과 비교되는 더 견고한 이벤트 발행 패턴을 다룸.
