# LOG006 — 챕터 6: 비관적 락 (`SELECT FOR UPDATE`) + 데드락 재현 및 해결

전체 진행도: [`README.md`](../README.md)

## 목표
챕터 4의 동시성 문제를 이번엔 비관적 락으로 해결한다. 낙관적 락(챕터 5)과 접근 자체가 다르다 — 낙관적 락은 "충돌 감지 후 재시도", 비관적 락은 "아예 못 건드리게 막고 대기". 그리고 비관적 락의 대표적 부작용인 데드락도 재현하고 해결한다.

## 시작 전 짚었던 것
1. `SELECT ... FOR UPDATE`로 조회하면 그 행에 락이 걸리고, 다른 트랜잭션이 같은 행을 잠그려 하면 예외 없이 그냥 대기한다 — 챕터 5와 달리 **재시도 로직이 필요 없다.**
2. **데드락 조건**: A→B 이체 트랜잭션과 B→A 이체 트랜잭션이 동시에 실행되면, 하나는 A를 먼저 잠그고 B를 기다리고 다른 하나는 B를 먼저 잠그고 A를 기다리는 순환 대기가 생길 수 있다. PostgreSQL이 이를 자동 감지해 한쪽을 강제 실패시킨다.
3. 데드락을 확실히 재현하려면 두 스레드가 "각자 첫 번째 락은 잡고 두 번째 락을 기다리는" 순간을 정확히 맞춰야 한다 — 챕터 2와 같은 `CountDownLatch` 정밀 제어가 필요.
4. 표준 해법은 **락 획득 순서를 항상 일정하게 강제**하는 것 (예: 계좌 ID가 작은 쪽부터 잠금).

## 핵심 코드

**`infra/AccountRepository.java`** (비관적 락 메서드 추가)
```java
public interface AccountRepository extends JpaRepository<Account, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from Account a where a.id = :id")
  Optional<Account> findByIdForUpdate(@Param("id") Long id);

}
```
(처음엔 `@Param("id")` 없이 작성했는데도 컴파일러의 `-parameters` 옵션 덕분에 문제없이 동작했음 — 다만 컴파일 옵션에 의존하는 방식이라 취약할 수 있어, 명시적으로 `@Param("id")`를 붙여 확실하게 고정함.)

**`app/PessimisticTransferService.java`** — 락 순서 고정 전/후 비교

| | 락 순서 고정 **전** (데드락 재현 당시) | 락 순서 고정 **후** (최종) |
|---|---|---|
| 락 획득 방식 | `fromId` → `toId` 순서 그대로 (이체 방향에 종속) | 항상 `Math.min(fromId, toId)` → `Math.max(...)` 순서 |
| A→B, B→A 동시 실행 | 순환 대기 발생 가능 → 데드락 | 둘 다 같은 계좌를 먼저 잠그려 경쟁 → 진 쪽은 단순 대기, 데드락 불가 |

고정 전(챕터 5 스타일과 동일하게 `fromId`/`toId` 순서 그대로 사용):
```java
  @Transactional
  public void transfer(Long fromId, Long toId, BigDecimal amount) {
    Account from = accountRepository.findByIdForUpdate(fromId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + fromId));
    from.withdraw(amount);
    accountRepository.save(from);

    Account to = accountRepository.findByIdForUpdate(toId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + toId));
    to.deposit(amount);
    accountRepository.save(to);
  }
```

최종(락 순서 고정):
```java
  @Transactional
  public void transfer(Long fromId, Long toId, BigDecimal amount) {
    long lowerId = Math.min(fromId, toId);
    long higherId = Math.max(fromId, toId);

    Map<Long, Account> accounts = new HashMap<>();
    accounts.put(lowerId, accountRepository.findByIdForUpdate(lowerId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + lowerId)));
    accounts.put(higherId, accountRepository.findByIdForUpdate(higherId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + higherId)));

    Account from = accounts.get(fromId);
    Account to = accounts.get(toId);
    from.withdraw(amount);
    accountRepository.save(from);
    to.deposit(amount);
    accountRepository.save(to);
  }
```

**`test/app/PessimisticTransferServiceTest.java`** — 10스레드 동시 출금, 재시도 없이도 정확한지 확인 (챕터 5의 `TransferRetryServiceTest`와 동일한 구조, `transferRetryService.transferWithRetry` 대신 `pessimisticTransferService.transfer` 직접 호출).

**`test/app/DeadlockTest.java`** — 두 개의 테스트:
```java
  @Test
  @DisplayName("서로 반대 순서로 락을 잡으면 데드락이 발생하고, 한쪽만 실패한다")
  void oppositeLockOrderCausesDeadlock() throws Exception {
    // TransactionTemplate으로 직접 트랜잭션 제어 (transfer()를 거치지 않음 — 락 순서 고정 후에도 이 테스트는 그대로 유효)
    // 스레드 1: A 잠금 → (동기화) → B 잠금 시도
    // 스레드 2: B 잠금 시도 → (동기화) → A 잠금 시도
    // → 정확히 1개만 실패해야 함
  }

  @Test
  @DisplayName("락 순서를 고정하면 반대 방향 동시 이체에서도 데드락이 안 난다")
  void consistentLockOrderPreventsDeadlock() throws Exception {
    // readyLatch/startLatch로 최대한 동시에 pessimisticTransferService.transfer()를
    // A→B, B→A 양방향으로 호출 → 실패 0개여야 함
  }
```
`oppositeLockOrderCausesDeadlock`은 `TransactionTemplate`으로 직접 락 순서를 조작하기 때문에 `transfer()`가 고쳐진 뒤에도 데드락 재현 자체는 그대로 유효하다 — 별도 "버그 버전" 메서드를 남겨둘 필요가 없었던 이유.

## 재현 결과 — 데드락
```
WARN ... SQLState: 40P01
ERROR: deadlock detected
  Detail: Process 69 waits for ShareLock on transaction 735; blocked by process 68.
Process 68 waits for ShareLock on transaction 736; blocked by process 69.
[T1] 실패: org.springframework.dao.CannotAcquireLockException - ...
실패한 트랜잭션 수: 1
```
- Postgres가 순환 대기를 직접 로그로 보여줌.
- `SQLState: 40P01` — Postgres 표준 데드락 감지 코드.
- Spring이 `CannotAcquireLockException`(벤더 중립 `DataAccessException` 계열)으로 번역.
- 정확히 1개만 실패, 나머지는 정상 진행 — Postgres가 데드락 감지 시 한쪽만 희생시키고 나머지는 계속 진행시켜준다는 것도 확인.
- 예상 못 했던 디테일: Hibernate가 `PESSIMISTIC_WRITE`를 `FOR UPDATE`가 아니라 Postgres의 더 가벼운 락 모드인 **`FOR NO KEY UPDATE`**로 변환함 (외래키 참조 무결성 체크를 방해하지 않는 락).

## 해결 결과
락 순서를 고정한 뒤 `consistentLockOrderPreventsDeadlock`을 돌리면 실패 0개 — A→B/B→A 양방향 동시 이체에도 데드락이 재현되지 않음을 확인.

## 설계 결정 — `transfer()`를 직접 수정 vs 별도 메서드
락 순서 고정을 별도 메서드(`transferSafely` 등)로 분리할지 논의. **`transfer()`를 직접 수정하는 쪽으로 결정** — 이유:
- `transfer()`는 "안전하게 이체한다"는 계약 자체를 갖는 메서드라, 데드락에 취약한 버전을 남겨두면 나중에 실수로 호출될 위험이 있음 (챕터 1에서 `TransferService.transfer()`를 바로 고쳤던 것과 같은 논리).
- `oppositeLockOrderCausesDeadlock` 테스트가 `transfer()`를 거치지 않고 `TransactionTemplate`으로 직접 락 순서를 조작하기 때문에, `transfer()`를 고쳐도 버그 재현 기록 자체는 별도 메서드 없이도 그대로 보존됨.
- (챕터 5의 `TransferRetryService`는 상황이 달랐음 — 재시도는 `transfer()` 자체의 결함이 아니라 그 위에 얹는 별개 책임이라 새 클래스가 자연스러웠음. 이번엔 "락 순서"가 `transfer()` 자체의 정확성 문제라 챕터 1 케이스에 더 가까움.)

## 시행착오
`consistentLockOrderPreventsDeadlock`에 처음엔 `CountDownLatch` 없이 그냥 스레드 풀에 두 작업을 던졌는데, "라치 없이도 되나?"는 질문이 나옴 — 라치가 없으면 두 스레드가 실제로 안 겹치고 순차적으로 실행돼도 테스트가 통과해버려서, **동시성 경합 자체가 검증 안 된 채로 통과하는 약한 테스트**가 될 위험이 있었음. `readyLatch`/`startLatch`로 최대한 겹치게 강제한 뒤에야 이 테스트가 진짜 의미 있는 검증이 됨.

## 완료 체크리스트
- [x] `AccountRepository.findByIdForUpdate` + `PessimisticTransferService` 작성
- [x] 재시도 없이도 동시 출금이 정확히 처리됨을 확인 (챕터 5와 결과 비교)
- [x] 데드락 재현 (`CannotAcquireLockException`, 정확히 1개만 실패)
- [x] 락 순서 고정으로 해결, 동시 반대방향 이체에도 데드락 안 남을 확인
- [x] 이 로그 문서 작성

## ADR

**Decision**
- 비관적 락은 `@Lock(LockModeType.PESSIMISTIC_WRITE)` + `@Query`로 구현, 낙관적 락(`TransferRetryService`)과 별개로 `PessimisticTransferService`를 유지 — 두 접근을 비교할 수 있도록 둘 다 남김.
- 데드락 해결은 "항상 낮은 ID부터 잠근다"는 락 순서 고정 방식.
- 락 순서 고정은 `transfer()` 자체를 수정 — 별도의 "안전 버전" 메서드를 만들지 않음.

**Drivers**
- 비관적 락은 재시도가 필요 없다는 게 낙관적 락과의 핵심 차이 — 대신 블로킹/데드락이라는 다른 트레이드오프가 있음을 직접 겪어야 함.
- `transfer()`가 "안전한 이체"라는 계약을 갖는 이상, 알려진 결함(데드락 취약)이 있는 버전을 프로덕션 코드에 남겨두는 건 위험.

**Alternatives considered**
- 데드락 방지를 위해 애플리케이션 레벨 락(예: `synchronized`, 분산 락) 도입 — 기각: DB가 이미 제공하는 락 순서 고정만으로 충분하고, 별도 락 매커니즘은 이 시점에 불필요한 복잡도.
- 락 순서 고정을 별도 메서드로 분리 — 기각: `transfer()`의 계약 자체를 고치는 문제라 챕터 1과 같은 논리로 원본을 직접 수정. 버그 재현 테스트는 `transfer()`와 무관하게 독립적으로 구성해뒀기 때문에 분리할 필요가 없었음.

**Consequences**
- `PessimisticTransferService.transfer()`는 항상 낮은 ID 계좌부터 잠근다 — 이후 이 서비스에 새 메서드를 추가할 때도 여러 계좌를 다룬다면 같은 순서 원칙을 지켜야 함.
- 동시성 테스트를 짤 때 "겹치는 걸 보장 안 하면 테스트가 약해질 수 있다"는 걸 재확인 — 관련 없어 보이는 동시 실행 테스트에도 `readyLatch`/`startLatch` 패턴을 기본으로 고려.

**Follow-ups**
- 낙관적 락(챕터 5) vs 비관적 락(챕터 6) 트레이드오프 정리는 커리큘럼상 별도 챕터는 없지만, README나 추후 회고에서 비교표로 정리해볼 가치 있음.
- 챕터 7(이체 한도 동시성 제어)에서 이번 챕터의 비관적 락 패턴을 재사용할 가능성.
