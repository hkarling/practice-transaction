# LOG005 — 챕터 5: 낙관적 락 (`@Version`) + 재시도 전략

전체 진행도: [`README.md`](../README.md)

## 목표
챕터 4에서 재현한 Lost Update를 결정론적으로 고친다. `Account`에 `@Version`을 추가해 동시 수정 충돌을 감지하고, 충돌한 쪽은 재시도한다.

## 시작 전 짚었던 것
- `@Version`이 붙으면 Hibernate가 `UPDATE ... WHERE id=? AND version=?`을 날리고, 매치 안 되면(=누군가 먼저 커밋) `ObjectOptimisticLockingFailureException`을 던진다.
- 실패한 트랜잭션은 재사용 불가(rollback-only) — 재시도는 **새 트랜잭션**으로 다시 시도해야 한다.
- 챕터 3의 self-invocation 문제가 여기서도 재현됨: 재시도 루프를 `TransferService` 안에서 `this.transfer()`로 돌리면 `@Transactional`이 프록시를 안 거쳐 무력화된다. 그래서 재시도 루프는 별도 빈(`TransferRetryService`)에 둔다.

## 재시도 방식 결정
Spring Retry(`@Retryable`, 새 의존성 필요) 대신 **수동 재시도 루프**를 선택 — 의존성 추가 없이 플레인 자바로 짜서 동작이 눈에 명확히 보이는 게 학습 목적에 더 맞는다고 판단.

## 구현
- `Account`에 `@Version private Long version;` 추가.
- `TransferRetryService` — `TransferService.transfer()`를 호출하고, `ObjectOptimisticLockingFailureException`만 잡아서 최대 20회 재시도. `IllegalStateException`(잔액 부족)은 재시도해도 결과가 안 바뀌는 진짜 실패라 그대로 전파.
- 테스트를 두 단계로 분리:
  - `ConcurrentTransferTest`(챕터 4 재사용, assertion만 수정) — `TransferService`를 재시도 없이 그대로 호출. `@Version`만으로도 **데이터 정합성은 지켜진다**는 걸 확인 (`성공 횟수 × 금액 == 실제 감소액`이 항상 성립 — 챕터 4 때와 반대로 `isEqualByComparingTo`로 "같다"를 검증).
  - `TransferRetryServiceTest`(신규) — `TransferRetryService`를 호출. 정확히 5개 성공, 5개는 진짜 잔액 부족으로 실패, 최종 잔액 정확히 0이라는 **완전히 결정론적인 결과**를 검증.

## 핵심 코드 (챕터 5 완료 시점, 리팩터링 반영 전)

**`domain/Account.java`** (`@Version` 필드만 추가)
```java
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
public class Account {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String ownerName;

  private BigDecimal balance;

  @Version
  private Long version;

  public Account(String ownerName, BigDecimal balance) {
    this.ownerName = ownerName;
    this.balance = balance;
  }

  public void withdraw(BigDecimal amount) {
    if (balance.compareTo(amount) < 0) {
      throw new IllegalStateException("잔액 부족: " + ownerName);
    }
    balance = balance.subtract(amount);
  }

  public void deposit(BigDecimal amount) {
    balance = balance.add(amount);
  }
}
```

**`app/transfer/TransferRetryService.java`** (신규, 챕터 7 이후 `app.transfer` 하위로 이동)
```java
@Slf4j
@RequiredArgsConstructor
@Service
public class TransferRetryService {

  private static final int MAX_RETRY = 20;

  private final TransferService transferService;

  public void transferWithRetry(Long fromId, Long toId, BigDecimal amount) {
    for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
      try {
        transferService.transfer(fromId, toId, amount);
        return;
      } catch (ObjectOptimisticLockingFailureException e) {
        log.info("낙관적 락 충돌, 재시도 {}/{}", attempt, MAX_RETRY);
      }
    }
    throw new IllegalStateException("재시도 " + MAX_RETRY + "회 초과");
  }
}
```

**`test/app/transfer/ConcurrentTransferTest.java`** (챕터 4 재사용, assertion만 반전)
```java
  @Test
  @DisplayName("여러 스레드가 동시에 출금하면 Lost Update로 잔액이 안 맞을 수 있다")
  void concurrentWithdrawalsCauseLostUpdate() throws Exception {
    // ... (스레드 세팅은 챕터 4와 동일, LOG004 참고)

    Account reloaded = accountRepository.findById(from.getId()).orElseThrow();
    BigDecimal expectedBalance = new BigDecimal("10000")
        .subtract(withdrawAmount.multiply(BigDecimal.valueOf(successCount.get())));

    assertThat(reloaded.getBalance()).isEqualByComparingTo(expectedBalance); // 챕터 4: isNotEqualByComparingTo 였음
  }
```

**`test/app/transfer/TransferRetryServiceTest.java`** (신규)
```java
@Slf4j
@Testcontainers
@SpringBootTest
class TransferRetryServiceTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @Autowired
  private TransferRetryService transferRetryService;

  @Autowired
  private AccountRepository accountRepository;

  @Test
  @DisplayName("재시도까지 더하면 동시 출금이 정확히, 낭비 없이 처리된다")
  void concurrentWithdrawalsAreHandledCorrectlyWithRetry() throws Exception {
    Account from = accountRepository.save(new Account("alice", new BigDecimal("10000")));
    Account to = accountRepository.save(new Account("bob", new BigDecimal("0")));
    BigDecimal withdrawAmount = new BigDecimal("2000");
    int threadCount = 10;

    CountDownLatch readyLatch = new CountDownLatch(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger(0);
    List<Exception> failures = new CopyOnWriteArrayList<>();

    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    for (int i = 0; i < threadCount; i++) {
      pool.submit(() -> {
        try {
          readyLatch.countDown();
          startLatch.await();
          transferRetryService.transferWithRetry(from.getId(), to.getId(), withdrawAmount);
          successCount.incrementAndGet();
        } catch (Exception e) {
          failures.add(e);
        }
      });
    }

    readyLatch.await(5, TimeUnit.SECONDS);
    startLatch.countDown();
    pool.shutdown();
    pool.awaitTermination(10, TimeUnit.SECONDS);

    Account reloaded = accountRepository.findById(from.getId()).orElseThrow();

    log.info("성공한 출금 수: {}, 실패한 출금 수: {}", successCount.get(), failures.size());
    log.info("최종 잔액: {}", reloaded.getBalance());
    failures.forEach(e -> log.info("실패 원인: {} - {}", e.getClass().getName(), e.getMessage()));

    assertThat(successCount.get()).isEqualTo(5);
    assertThat(failures).hasSize(5);
    assertThat(reloaded.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
  }
}
```

**`test/domain/IsolationLevelTest.java`의 `insertTestAccount`** (챕터 2 회귀 수정)
```java
  private long insertTestAccount(String ownerName, String balance) throws SQLException {
    String sql = "INSERT INTO account (owner_name, balance, version) VALUES (?, ?, 0) RETURNING id";
    // ... 나머지 동일
  }
```

## 결과
```
성공한 출금 수: 5, 실패한 출금 수: 5
최종 잔액: 0.00
실패 원인: java.lang.IllegalStateException - 잔액 부족: alice  (×5)
```
챕터 4와 달리 몇 번을 반복 실행해도 항상 같은 결과가 나옴 — 결정론적으로 고쳐졌다.

## 시행착오

**1) BigDecimal `equals()` vs `compareTo()`**
`isEqualTo(BigDecimal.ZERO)` 같은 형태(내부적으로 `.equals()` 사용)를 썼다가 `expected: 0 but was: 0.00`로 실패. `BigDecimal.equals()`는 scale(소수점 자릿수)까지 비교해서 `0`과 `0.00`을 다르다고 봄. `isEqualByComparingTo(...)`(`.compareTo()` 사용, scale 무시)로 바꿔서 해결 — 챕터 1에서 이미 겪은 것과 같은 원리, AssertJ에도 그대로 적용됨.

**2) 챕터 5의 스키마 변경이 챕터 2 테스트를 깨뜨림**
`Account`에 `@Version`을 추가하자 Hibernate가 `version` 컬럼을 NOT NULL로 생성. `IsolationLevelTest`(챕터 2, JPA 안 거치고 순수 JDBC로 직접 INSERT)가 `version`을 안 채워서 `PSQLException: null value in column "version" ... violates not-null constraint`로 깨짐. `insertTestAccount`의 INSERT문에 `version` 컬럼과 리터럴 `0`을 추가해서 수정. **엔티티 필드 하나 추가가 다른 챕터의 raw SQL 테스트에 영향을 줄 수 있다**는 걸 실제로 겪음 — 테스트 스위트 전체를 계속 돌려보는 습관이 왜 중요한지 보여주는 사례.

## 테스트 컨테이너/컨텍스트 공유 리팩터링 (챕터 5 마무리 후 진행)
테스트 클래스가 6개로 늘면서, 클래스마다 `@Testcontainers`로 별도 컨테이너 + Spring 컨텍스트를 매번 새로 띄워 빌드가 느려지는 게 눈에 띔. Testcontainers의 "싱글턴 컨테이너 패턴"으로 정리:

**`test/AbstractIntegrationTest.java`** (신규 공통 베이스)
```java
public abstract class AbstractIntegrationTest {

  static final PostgreSQLContainer<?> POSTGRES;

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16");
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

}
```
컨테이너를 정적 초기화 블록에서 한 번만 띄우고 `stop()`을 안 부름 — JVM 종료 시 Testcontainers의 Ryuk이 정리. 6개 테스트 클래스 전부 `@Testcontainers`/`@Container`/`@ServiceConnection`/자체 `PostgreSQLContainer` 필드를 지우고 `extends AbstractIntegrationTest`로 교체:
```java
@SpringBootTest
class TransferServiceTest extends AbstractIntegrationTest {
  // ...
}
```
같은 커넥션 정보를 모든 테스트가 공유하게 되면서, Spring의 ApplicationContext 캐싱도 같이 적용됨 (컨테이너뿐 아니라 Spring 컨텍스트 자체도 재사용).

**효과**: `./gradlew clean test` 기준 `Creating container for image: postgres:16` 로그가 6번 → **1번**으로 줄음. 전체 빌드 시간도 체감상 확실히 단축.

## 완료 체크리스트
- [x] `Account`에 `@Version` 추가
- [x] `TransferRetryService` 작성 — 낙관적 락 충돌만 재시도, 최대 20회
- [x] `ConcurrentTransferTest` — `@Version`만으로 데이터 정합성 유지됨을 확인
- [x] `TransferRetryServiceTest` — 재시도까지 더해 완전히 결정론적인 결과 확인
- [x] 챕터 2 회귀(`IsolationLevelTest`의 `version` NOT NULL 위반) 수정
- [x] 테스트 컨테이너/Spring 컨텍스트 공유 리팩터링 (`AbstractIntegrationTest`)
- [x] 이 로그 문서 작성 (핵심 코드 스냅샷 포함)

## ADR

**Decision**
- 낙관적 락(`@Version`)으로 동시 수정 충돌을 감지하고, 별도 빈(`TransferRetryService`)의 수동 재시도 루프로 복구한다.
- 재시도는 `ObjectOptimisticLockingFailureException`만 잡는다 — 비즈니스 실패(`IllegalStateException`)는 재시도하지 않는다.
- Spring Retry 같은 프레임워크 의존성 대신 플레인 자바 루프를 사용한다.

**Drivers**
- 재시도는 반드시 새 트랜잭션에서 일어나야 하고, self-invocation 문제 때문에 별도 빈이 필요함.
- 학습 목적상 재시도 동작이 코드에 명시적으로 드러나는 게 프레임워크 매직보다 낫다고 판단.

**Alternatives considered**
- Spring Retry(`@Retryable`) — 기각(이번엔): 새 의존성 + AOP 프록시 순서 설정이 추가 디버깅 포인트가 될 수 있어, 수동 루프로 핵심 개념부터 익히기로 함. 나중에 실무 패턴으로 다시 다뤄볼 가치는 있음.
- 챕터 4 테스트를 그대로 두고 재시도용 테스트만 추가 — 기각(부분적으로): 대신 챕터 4 테스트의 assertion만 "다르다"→"같다"로 바꿔서, `@Version` 하나만으로 뭐가 해결되고 뭐가 안 되는지(정합성은 지켜지지만 비효율적)를 보여주는 별도 지점으로 남김.

**Consequences**
- `TransferRetryService.transferWithRetry()`가 이제 `TransferService.transfer()`의 공식적인 "안전한" 진입점이다 — 동시성이 걱정되는 곳에서는 `TransferService`를 직접 호출하지 말고 이걸 써야 함.
- 다른 엔티티 필드 변경 시에도 raw SQL을 쓰는 테스트(`IsolationLevelTest` 등)에 영향이 없는지 항상 전체 테스트를 돌려 확인해야 한다는 걸 재확인.

**Follow-ups**
- 챕터 6(비관적 락)에서 같은 문제를 `SELECT FOR UPDATE`로 풀어보고 낙관적 락과 트레이드오프 비교.
- 새 테스트 클래스를 추가할 땐 `AbstractIntegrationTest`를 상속하는 걸 기본으로 한다 (개별 `@Testcontainers` 세팅 반복 금지).
