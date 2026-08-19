# LOG013 — 챕터 13: SAGA Orchestration

전체 진행도: [`README.md`](../README.md)

## 목표
챕터 12와 같은 시나리오(이체 = 출금 + 입금)를 **중앙 조정자가 각 단계를 명시적으로 호출하고, 실패하면 명시적으로 보상을 호출하는 방식**으로 재구현. 이벤트/리스너가 아니라 순차적인 메서드 호출.

## 시작 전 짚었던 것
- **가장 중요한 함정**: 조정자 메서드(`executeTransfer`)에 `@Transactional`을 붙이면 안 됨 — 붙이면 각 단계가 전부 하나의 트랜잭션에 합류해서 그냥 평범한 로컬 트랜잭션이 되어버림. 실패 시 자동 롤백되니 겉보기엔 "잘 동작하는 것처럼" 보이지만, 실제 여러 서비스/DB로 나뉜 환경이었다면 애초에 하나의 트랜잭션으로 묶을 수조차 없었을 것 — 우연히 맞는 것처럼 보이는 게 가장 위험한 함정.
- 이번 챕터의 기본 버그는 챕터 12와 같음(보상 호출을 깜빡하면 영구 미반영) — 다만 이벤트가 아니라 조정자가 `try/catch`로 명시적으로 보상.

## 새 하위패키지 — `app.saga.orchestration`
챕터 12(`app.saga.choreography`)와 같은 클래스명(`WithdrawalService`, `DepositService`)을 쓰려다 **Spring 빈 이름 충돌**(`ConflictingBeanDefinitionException` — 기본 빈 이름은 패키지 무시하고 클래스 단순명 기준)을 겪고 `OrchestratedWithdrawalService`/`OrchestratedDepositService`로 개명.

## 핵심 코드 — 기본 2단계

**`app/saga/orchestration/OrchestratedWithdrawalService.java`**
```java
@RequiredArgsConstructor
@Service
public class OrchestratedWithdrawalService {

  private final AccountRepository accountRepository;

  @Transactional
  public void withdraw(Long fromId, BigDecimal amount) {
    Account from = accountRepository.findById(fromId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + fromId));
    from.withdraw(amount);
    accountRepository.save(from);
  }

  @Transactional
  public void compensate(Long fromId, BigDecimal amount) {
    Account from = accountRepository.findById(fromId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + fromId));
    from.deposit(amount);
    accountRepository.save(from);
  }
}
```

**`app/saga/orchestration/OrchestratedDepositService.java`**
```java
@RequiredArgsConstructor
@Service
public class OrchestratedDepositService {

  private final AccountRepository accountRepository;

  @Transactional
  public void deposit(Long toId, BigDecimal amount) {
    Account to = accountRepository.findById(toId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + toId));
    to.deposit(amount);
    accountRepository.save(to);
  }
}
```

**`app/saga/orchestration/TransferSagaOrchestrator.java`** (최종 — `@Transactional` 절대 없음)
```java
@RequiredArgsConstructor
@Service
public class TransferSagaOrchestrator {

  private final OrchestratedWithdrawalService withdrawalService;
  private final OrchestratedDepositService depositService;

  public void executeTransfer(Long fromId, Long toId, BigDecimal amount) {
    withdrawalService.withdraw(fromId, amount);
    try {
      depositService.deposit(toId, amount);
    } catch (Exception e) {
      withdrawalService.compensate(fromId, amount);
      throw e;
    }
  }
}
```
(버그 버전엔 `try/catch`도 보상 호출도 없었음 — 출금 후 입금만 순서대로 호출.)

## 재현 및 수정 결과 — 2단계
- 버그 버전: `7000.00` (출금만 영구 반영)
- 수정 버전: `10000.00` (보상 성공)

## Choreography vs Orchestration 비교
| | 챕터 12 Choreography | 챕터 13 Orchestration |
|---|---|---|
| 조정 방식 | 각 서비스가 이벤트를 구독해 알아서 반응 | 중앙 조정자가 순서/보상을 명시적으로 호출 |
| 단계 간 통신 | `@TransactionalEventListener(AFTER_COMMIT)` | 평범한 메서드 호출 |
| 전체 흐름 파악 | 여러 서비스에 흩어진 리스너를 다 따라가야 함 ("이벤트 스프") | 조정자 메서드 하나에 전체 흐름이 다 보임 |
| 보상 책임 | 각 서비스가 자기 행동을 자기가 보상 | 조정자가 모든 보상 호출을 지시 |
| 서비스 간 결합 | 느슨함(이벤트만 알면 됨) | 조정자가 모든 서비스를 알아야 함(결합도 높음) |
| 실무 적합성 | 단계가 적고 서비스가 독립적일 때 | 단계가 많거나 전체 흐름 가시성이 중요할 때 |

## 참고 — 실제 마이크로서비스 환경에서의 Orchestration
지금 예제는 전부 같은 JVM 안에 있어서 조정자가 다른 서비스를 그냥 메서드로 호출했지만, 진짜 여러 서비스로 나뉘면 달라지는 점들:

1. **조정자 자체가 별도 서비스** — 다른 서비스들의 코드를 알지도, 같은 프로세스에 있지도 않음.
2. **메서드 호출 대신 네트워크 호출** — 동기(REST/gRPC, 응답 대기, 가용성이 서로 묶임) 또는 비동기(메시지 큐로 커맨드 발행 → 참가자가 처리 후 완료/실패 이벤트로 응답, 더 견고하지만 상관관계 ID·타임아웃 등 복잡도 추가).
3. **사가 상태를 영속화해야 함** — 지금 `List<Runnable> compensations`는 메서드가 끝나면 사라지는 지역변수라도 괜찮았지만, 비동기 환경에서는 조정자가 재시작돼도 "지금 몇 단계까지 왔는지"를 알아야 함 → 보통 조정자 자신의 DB에 `saga_instance` 같은 테이블로 기록.
4. **보상도 커맨드/응답 방식** — 실패 시 이미 완료된 단계들에게 역순으로 보상 커맨드를 발행, 정방향과 같은 메커니즘.
5. **멱등성 문제가 자연스럽게 따라옴** — 네트워크는 메시지를 중복 전달할 수 있어(at-least-once), 각 서비스가 같은 커맨드를 두 번 받아도 한 번만 처리하도록 만들어야 함 → 챕터 15(멱등성 설계)로 연결.

**실무 도구**: 이런 상태 영속화·재시도·타임아웃·모니터링을 직접 구현하는 대신 Camunda, Netflix Conductor, AWS Step Functions, Temporal 같은 워크플로우/사가 엔진을 사용하는 경우가 많다 — 사가를 상태 머신으로 정의하면 엔진이 나머지를 대신 처리.

## 3단계 확장 — 보상 스택 패턴

### 시나리오
수수료 징수 → 출금 → 입금, 3단계로 확장.

### 새 코드

**`app/saga/orchestration/OrchestratedFeeService.java`**
```java
@RequiredArgsConstructor
@Service
public class OrchestratedFeeService {

  private final AccountRepository accountRepository;

  @Transactional
  public void chargeFee(Long fromId, Long feeAccountId, BigDecimal fee) {
    Account from = accountRepository.findById(fromId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + fromId));
    from.withdraw(fee);
    accountRepository.save(from);

    Account feeAccount = accountRepository.findById(feeAccountId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + feeAccountId));
    feeAccount.deposit(fee);
    accountRepository.save(feeAccount);
  }

  @Transactional
  public void compensate(Long fromId, Long feeAccountId, BigDecimal fee) {
    Account feeAccount = accountRepository.findById(feeAccountId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + feeAccountId));
    feeAccount.withdraw(fee);
    accountRepository.save(feeAccount);

    Account from = accountRepository.findById(fromId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + fromId));
    from.deposit(fee);
    accountRepository.save(from);
  }
}
```

**`app/saga/orchestration/MultiStepTransferSagaOrchestrator.java`** (최종 — 보상 스택)
```java
@RequiredArgsConstructor
@Service
public class MultiStepTransferSagaOrchestrator {

  public static final BigDecimal FEE = new BigDecimal("100");

  private final OrchestratedFeeService feeService;
  private final OrchestratedWithdrawalService withdrawalService;
  private final OrchestratedDepositService depositService;

  public void executeTransfer(Long fromId, Long toId, Long feeAccountId, BigDecimal amount) {
    List<Runnable> compensations = new ArrayList<>();
    try {
      feeService.chargeFee(fromId, feeAccountId, FEE);
      compensations.add(() -> feeService.compensate(fromId, feeAccountId, FEE));

      withdrawalService.withdraw(fromId, amount);
      compensations.add(() -> withdrawalService.compensate(fromId, amount));

      depositService.deposit(toId, amount);
    } catch (Exception e) {
      Collections.reverse(compensations);
      compensations.forEach(Runnable::run);
      throw e;
    }
  }
}
```
(버그 버전엔 `try` 블록에 3단계를 순서대로 호출하고, `catch`에서 **무조건 두 보상을 다 호출**했음 — 성공 여부와 무관하게.)

**`test/app/saga/orchestration/MultiStepTransferSagaOrchestratorTest.java`**
```java
@SpringBootTest
class MultiStepTransferSagaOrchestratorTest extends AbstractIntegrationTest {

  @Autowired
  private MultiStepTransferSagaOrchestrator orchestrator;

  @Autowired
  private AccountRepository accountRepository;

  @Test
  @DisplayName("실패하지 않은 단계까지 보상하면 안 된다")
  void shouldNotCompensateStepsThatNeverSucceeded() {
    Account from = accountRepository.save(new Account("alice", new BigDecimal("950")));
    Account to = accountRepository.save(new Account("bob", new BigDecimal("0")));
    Account feeAccount = accountRepository.save(new Account("bank-fee", new BigDecimal("0")));

    assertThatThrownBy(() -> orchestrator.executeTransfer(from.getId(), to.getId(), feeAccount.getId(), new BigDecimal("3000")))
        .isInstanceOf(IllegalStateException.class);

    Account reloadedFrom = accountRepository.findById(from.getId()).orElseThrow();
    assertThat(reloadedFrom.getBalance()).isEqualByComparingTo("950");
  }
}
```

### 재현 및 수정 결과 — 3단계
초기 잔액 `950`, 수수료 `100`, 이체 시도 `3000`:
- 수수료 징수 성공 → `850`
- 출금(`3000`) 시도 → 잔액 부족(`850 < 3000`)으로 실패, 잔액은 그대로 `850`
- **버그 버전(무조건 전부 보상)**: 수수료 환불(`+100`) + **출금 "환불"**(`+3000`, 실제로 안 일어난 출금을 되돌림) → `3950.00` — 있지도 않은 돈이 생겨버림.
- **수정 버전(보상 스택)**: 성공한 수수료 징수만 스택에 있었으므로 그것만 역순 보상 → `950.00` — 정확히 원래대로 복원.

## 완료 체크리스트
- [x] `OrchestratedWithdrawalService`/`OrchestratedDepositService` + `TransferSagaOrchestrator`(보상 없는 버그 버전) 작성 (`app.saga.orchestration` 신설)
- [x] 2단계 테스트로 재현(`7000.00`) 및 수정(`10000.00`)
- [x] Choreography와의 비교표 정리
- [x] `OrchestratedFeeService` 추가, 3단계로 확장
- [x] 순진한 보상(무조건 전부 호출)의 과잉보상 버그 재현 (`3950.00`)
- [x] 보상 스택 패턴으로 수정 (`950.00`)
- [x] 이 로그 문서 작성

## ADR

**Decision**
- 조정자 메서드는 `@Transactional`을 붙이지 않는다 — 각 단계가 독립 커밋되는 진짜 SAGA를 유지하기 위해.
- 실패 시 보상은 **성공한 단계만** 역순으로 실행한다 — "보상 스택"(`List<Runnable>`)으로 구현.
- 2단계와 3단계 조정자를 별도 클래스(`TransferSagaOrchestrator`/`MultiStepTransferSagaOrchestrator`)로 유지 — 패턴의 진화를 나란히 비교할 수 있게.

**Drivers**
- 조정자에 실수로 `@Transactional`을 붙이면 "우연히 동작하는 것처럼 보이지만 실제로는 SAGA가 아닌" 상태가 되는 함정을 실제로 경계해야 함.
- 단계가 늘어날 때 "무조건 전부 보상"하는 순진한 접근이 실제 심각한 버그(과잉 보상, 없던 돈 생성)로 이어짐을 직접 확인.

**Alternatives considered**
- 중첩 `try/catch`로 단계별 보상 처리 — 기각: 단계가 늘어날수록 코드가 기하급수적으로 지저분해지고, 한 단계라도 빠뜨리면 위와 같은 과잉보상 버그가 나기 쉬움. 보상 스택이 훨씬 간결하고 안전.
- Axon/Camunda 같은 SAGA 프레임워크 도입 — 기각: 이 프로젝트 규모에서는 과함, 수동 보상 스택으로 충분히 핵심 개념을 익힐 수 있음.

**Consequences**
- 앞으로 SAGA 단계가 더 늘어나도 같은 보상 스택 패턴을 재사용하면 됨.
- 보상 액션(`Runnable`)들은 각자 독립된 `@Transactional`을 가지므로, 보상 자체가 실패할 가능성은 아직 다루지 않음 (챕터 15 멱등성에서 연결 가능).

**Follow-ups**
- 챕터 14(Outbox 패턴)에서 SAGA의 이벤트 발행을 더 견고하게 만드는 방법을 다룸.
- 챕터 15(멱등성 설계)에서 "보상 자체가 재시도되면 어떻게 하나"를 다룰 수 있음 — 지금 보상 메서드들은 멱등하지 않음(두 번 실행하면 중복 환불됨).
