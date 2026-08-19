# LOG012 — 챕터 12: SAGA Choreography

전체 진행도: [`README.md`](../README.md)

## 목표
분산 트랜잭션 실습 시작. DB는 하나뿐이지만, "이체"를 독립된 두 서비스(출금/입금)가 각자 로컬 트랜잭션으로 처리하고 이벤트로만 통신하는 구조로 SAGA를 시뮬레이션한다. **Choreography**(안무형) — 중앙 지휘자 없이 각 서비스가 이벤트를 듣고 알아서 반응.

## 시작 전 짚었던 것
- 지금까지는 하나의 `@Transactional` 안에서 실패하면 자동 롤백됐지만, SAGA는 각 단계가 이미 독립적으로 커밋된다 — 실패해도 저절로 롤백 안 됨.
- 그래서 실패를 명시적으로 감지해서 **보상 트랜잭션**으로 되돌려야 한다. 깜빡하면 돈이 시스템에서 영구 증발.
- 챕터 10의 `@TransactionalEventListener(AFTER_COMMIT)`을 재사용 — 각 단계가 진짜 커밋된 뒤에만 다음 단계를 트리거해야 함.

## 새 하위패키지 — `app.saga.choreography`

## 핵심 코드

**`app/saga/choreography/WithdrawnEvent.java`** / **`DepositFailedEvent.java`**
```java
public record WithdrawnEvent(String sagaId, Long fromId, Long toId, BigDecimal amount) {
}

public record DepositFailedEvent(String sagaId, Long fromId, Long toId, BigDecimal amount, String reason) {
}
```

**`app/saga/choreography/WithdrawalService.java`** (최종 — 보상 리스너 포함)
```java
@RequiredArgsConstructor
@Service
public class WithdrawalService {

  private final AccountRepository accountRepository;
  private final ApplicationEventPublisher eventPublisher;

  @Transactional
  public void withdraw(String sagaId, Long fromId, Long toId, BigDecimal amount) {
    Account from = accountRepository.findById(fromId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + fromId));
    from.withdraw(amount);
    accountRepository.save(from);

    eventPublisher.publishEvent(new WithdrawnEvent(sagaId, fromId, toId, amount));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onDepositFailed(DepositFailedEvent event) {
    Account from = accountRepository.findById(event.fromId())
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + event.fromId()));
    from.deposit(event.amount());
    accountRepository.save(from);
  }
}
```

**`app/saga/choreography/DepositService.java`**
```java
@RequiredArgsConstructor
@Service
public class DepositService {

  private final AccountRepository accountRepository;
  private final ApplicationEventPublisher eventPublisher;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onWithdrawn(WithdrawnEvent event) {
    try {
      Account to = accountRepository.findById(event.toId())
          .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + event.toId()));
      to.deposit(event.amount());
      accountRepository.save(to);
    } catch (Exception e) {
      eventPublisher.publishEvent(
          new DepositFailedEvent(event.sagaId(), event.fromId(), event.toId(), event.amount(), e.getMessage()));
    }
  }
}
```

**`test/app/saga/choreography/WithdrawalServiceTest.java`**
```java
@SpringBootTest
class WithdrawalServiceTest extends AbstractIntegrationTest {

  @Autowired
  private WithdrawalService withdrawalService;

  @Autowired
  private AccountRepository accountRepository;

  @Test
  @DisplayName("입금이 실패하면 출금도 보상(환불)되어야 한다")
  void withdrawalShouldBeCompensatedWhenDepositFails() {
    Account from = accountRepository.save(new Account("alice", new BigDecimal("10000")));
    Long invalidToId = 999_999L;

    withdrawalService.withdraw(UUID.randomUUID().toString(), from.getId(), invalidToId, new BigDecimal("3000"));

    Account reloaded = accountRepository.findById(from.getId()).orElseThrow();
    assertThat(reloaded.getBalance()).isEqualByComparingTo("10000");
  }
}
```

## 시행착오

**1) `@TransactionalEventListener` + `@Transactional` 조합이 필요한 이유**
`AFTER_COMMIT` 시점엔 원래 트랜잭션(출금)이 이미 커밋된 뒤라, 리스너가 DB 작업(`accountRepository.save()`)을 하려면 자체 `@Transactional`이 필요함을 사용자가 먼저 질문. 처음엔 "활성 트랜잭션이 없다"고 답했으나, 이어진 실제 Spring 경고 메시지(`method runs in an undefined transactional state; use propagation REQUIRES_NEW or NOT_SUPPORTED, or annotate the method with @Async`)를 계기로 재확인 — 정확히는 원래 트랜잭션이 **정리(cleanup) 단계의 불명확한(undefined) 상태**에 있는 것이라, 기본 `REQUIRED`로는 그 애매한 상태에 합류할 위험이 있음. Spring이 아예 `REQUIRES_NEW`/`NOT_SUPPORTED`만 허용하도록 강제. → `@Transactional(propagation = Propagation.REQUIRES_NEW)`로 수정.

**2) 보상 리스너의 위치 — `WithdrawalService` vs `DepositService`**
"`onDepositFailed`는 `DepositService`에 있어야 하는 거 아니냐"는 질문. **`WithdrawalService`가 맞음** — SAGA의 원칙은 "보상은 자기가 한 일을 자기가 되돌린다". 실제 마이크로서비스라면 `DepositService`는 `from` 계좌가 있는 DB에 접근 권한조차 없을 수 있음 — 오직 자기 행동을 한 서비스만 그 보상 방법을 안다.

## 재현 및 수정 결과
- **버그 버전(보상 리스너 없음)**: `7000.00` — 출금(`3000`)만 영구 반영되고 환불 안 됨.
- **수정 버전(`WithdrawalService.onDepositFailed` 추가)**: `10000.00` — 입금 실패 → `DepositFailedEvent` → `WithdrawalService`가 자기 출금을 스스로 환불.

## 여러 단계 체인으로의 확장 (구현 없이 개념만 정리)
3단계 이상(예: 출금 → 환전 → 입금)으로 늘어나면, 실패는 역방향으로 체인을 타고 전파된다 — 각 단계는 "다음 단계의 실패 이벤트를 구독 → 내 작업 보상 → 내 보상/실패 이벤트를 발행"을 반복. 정방향 성공 체인과 정확히 대칭.

**직접 구현하지 않기로 결정**: 메커니즘이 2단계 예제와 동일하게 반복될 뿐이라 새로 배울 게 없다고 판단. Choreography의 실제 약점(체인이 길어지면 전체 흐름 파악이 어려운 "이벤트 스프" 문제)은 코드보다 개념 설명으로 충분히 전달됨 — 이 약점이 챕터 13(Orchestration)의 동기가 됨.

## 완료 체크리스트
- [x] `WithdrawnEvent`/`DepositFailedEvent` + `WithdrawalService`/`DepositService`(보상 없는 버그 버전) 작성 (`app.saga.choreography` 신설)
- [x] 테스트로 재현 (`7000.00`, 영구 미보상)
- [x] `WithdrawalService`에 보상 리스너 추가, 수정 확인 (`10000.00`)
- [x] `@TransactionalEventListener` + `@Transactional` 조합의 필요성(undefined 상태) 확인
- [x] 여러 단계 체인 확장 시나리오 개념 정리 (구현 없음)
- [x] 이 로그 문서 작성

## ADR

**Decision**
- SAGA 각 단계는 독립된 서비스(`WithdrawalService`/`DepositService`)의 독립된 로컬 트랜잭션으로 구현.
- 단계 간 통신은 `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` 조합.
- 보상 로직은 항상 "자기 행동을 한 서비스"가 소유 (탈중앙화 원칙).
- 3단계 이상 체인 확장은 구현하지 않고 개념 설명으로 대체.

**Drivers**
- 챕터 10에서 이미 검증한 `AFTER_COMMIT` 패턴을 SAGA 단계 간 통신에 그대로 재사용 가능함을 확인.
- Spring이 `@TransactionalEventListener` + `@Transactional`에 `REQUIRES_NEW`/`NOT_SUPPORTED`만 허용하도록 강제하는 이유(undefined 트랜잭션 상태)를 실제로 겪음.

**Alternatives considered**
- 보상 로직을 `DepositService`에 두기 — 기각: SAGA 원칙(자기 행동은 자기가 보상) 위반, 실제 마이크로서비스에서는 애초에 불가능한 설계.
- 3단계 이상 체인 직접 구현 — 기각: 동일 메커니즘의 반복이라 학습 가치 대비 비용이 큼. 개념 설명으로 충분.

**Consequences**
- 이후 SAGA 관련 챕터(13 Orchestration)에서 이번 챕터의 `WithdrawnEvent`/`DepositFailedEvent` 패턴과 대비하며 진행.
- 이벤트 기반 통신은 실제로는 인메모리(`ApplicationEventPublisher`)라 진짜 메시지 브로커(Kafka 등)의 네트워크 장애/재시도 문제는 다루지 않음 — 이 프로젝트의 의도적 단순화.

**Follow-ups**
- 챕터 13(Orchestration)에서 같은 시나리오를 중앙 조정자 방식으로 재구현, Choreography와 직접 비교.
- 챕터 15(멱등성 설계)에서 "보상 자체가 실패하면 어떻게 하나"(보상의 재시도/멱등성) 문제를 다룰 수 있음.
