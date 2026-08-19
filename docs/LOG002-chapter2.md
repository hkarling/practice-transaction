# LOG002 — 챕터 2: 격리 수준별 실험 (READ_UNCOMMITTED → SERIALIZABLE)

전체 진행도: [`README.md`](../README.md)

## 목표
같은 계좌 잔액을 한 트랜잭션 안에서 두 번 읽는 사이, 다른 트랜잭션이 값을 바꾸고 커밋하면 격리 수준별로 어떻게 다르게 보이는지 확인한다.

## 시작 전 짚었던 것
1. **PostgreSQL엔 진짜 READ_UNCOMMITTED가 없다.** MVCC 구조상 커밋 안 된 데이터는 애초에 다른 트랜잭션에서 안 보이므로, `READ_UNCOMMITTED`를 요청해도 내부적으로 `READ_COMMITTED`와 동일하게 동작한다.
2. **JPA 1차 캐시가 순수 격리수준 실험을 왜곡시킬 수 있다.** 같은 영속성 컨텍스트에서 `repository.findById()`를 두 번 부르면 두 번째는 DB를 다시 안 쏘고 캐시를 반환한다. 그래서 이번 챕터는 JPA를 거치지 않고 **순수 JDBC**로 두 커넥션을 직접 열어 실험했다.

## 구현
`IsolationLevelTest` — `@Autowired DataSource`에서 커넥션을 두 개 꺼내 스레드 두 개로 정확히 교차 실행:
- 스레드 A: 트랜잭션 시작(격리수준 파라미터) → 첫 읽기 → (B 대기) → 두 번째 읽기 → 커밋
- 스레드 B: (A의 첫 읽기 대기) → 값 업데이트 → 커밋

`CountDownLatch` 두 개로 순서를 강제: `firstReadDone`(A의 첫 읽기 완료 신호), `updateCommitted`(B의 커밋 완료 신호). 실험 로직을 `runNonRepeatableReadExperiment(int isolationLevel)` 헬퍼로 뽑아 두 테스트(`READ_COMMITTED`/`REPEATABLE_READ`)가 격리수준만 다르게 재사용.

`BigDecimal` 비교는 `assertEquals(0, compareTo(...))` 대신 AssertJ `isEqualByComparingTo()`로 — Spring Boot 생태계 표준이고, `assertj-core`가 `spring-boot-starter-data-jpa-test`에 이미 전이 의존성으로 포함돼 있음을 `./gradlew dependencies --configuration testCompileClasspath`로 확인 후 적용.

## 핵심 코드 (챕터 2 완료 시점)

**`test/domain/IsolationLevelTest.java`**
```java
@Slf4j
@Testcontainers
@SpringBootTest
class IsolationLevelTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @Autowired
  private DataSource dataSource;

  @Test
  @DisplayName("READ_COMMITTED는 같은 트랜잭션 안에서도 남이 커밋한 값을 다시 읽으면 바뀐다")
  void readCommittedAllowsNonRepeatableRead() throws Exception {
    BigDecimal[] result = runNonRepeatableReadExperiment(Connection.TRANSACTION_READ_COMMITTED);

    assertThat(result[0]).isEqualByComparingTo("10000");
    assertThat(result[1]).isEqualByComparingTo("5000"); // 바뀐 값이 보임 (non-repeatable read)
  }

  @Test
  @DisplayName("REPEATABLE_READ는 트랜잭션 시작 시점 스냅샷을 유지해 다시 읽어도 값이 안 바뀐다")
  void repeatableReadPreventsNonRepeatableRead() throws Exception {
    BigDecimal[] result = runNonRepeatableReadExperiment(Connection.TRANSACTION_REPEATABLE_READ);

    assertThat(result[0]).isEqualByComparingTo("10000");
    assertThat(result[1]).isEqualByComparingTo("10000"); // 원래 값 그대로 (스냅샷 유지)
  }

  private BigDecimal[] runNonRepeatableReadExperiment(int isolationLevel) throws InterruptedException, SQLException {
    long accountId = insertTestAccount("bob", "10000");

    CountDownLatch firstReadDone = new CountDownLatch(1);
    CountDownLatch updateCommitted = new CountDownLatch(1);
    BigDecimal[] result = new BigDecimal[2];

    ExecutorService pool = Executors.newFixedThreadPool(2);

    pool.submit(() -> {
      try (Connection conn = dataSource.getConnection()) {
        conn.setAutoCommit(false);
        conn.setTransactionIsolation(isolationLevel);

        result[0] = readBalance(conn, accountId);
        log.info("[A] 첫 번째 읽기: {}", result[0]);
        firstReadDone.countDown();

        updateCommitted.await(5, TimeUnit.SECONDS);
        result[1] = readBalance(conn, accountId);
        log.info("[A] 두 번째 읽기: {}", result[1]);

        conn.commit();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    pool.submit(() -> {
      try {
        firstReadDone.await(5, TimeUnit.SECONDS);
        try (Connection conn = dataSource.getConnection()) {
          conn.setAutoCommit(false);
          updateBalance(conn, accountId, "5000");
          conn.commit();
          log.info("[B] 업데이트 커밋 완료");
        }
        updateCommitted.countDown();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    pool.shutdown();
    pool.awaitTermination(10, TimeUnit.SECONDS);

    return result;
  }

  private long insertTestAccount(String ownerName, String balance) throws SQLException {
    String sql = "INSERT INTO account (owner_name, balance) VALUES (?, ?) RETURNING id";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, ownerName);
      ps.setBigDecimal(2, new BigDecimal(balance));
      ps.executeUpdate();
      try (ResultSet rs = ps.getGeneratedKeys()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private BigDecimal readBalance(Connection conn, long accountId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM account WHERE id = ?")) {
      ps.setLong(1, accountId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBigDecimal("balance");
      }
    }
  }

  private void updateBalance(Connection conn, long accountId, String balance) throws Exception {
    try (PreparedStatement ps = conn.prepareStatement("UPDATE account SET balance = ? WHERE id = ?")) {
      ps.setBigDecimal(1, new BigDecimal(balance));
      ps.setLong(2, accountId);
      ps.executeUpdate();
    }
  }
}
```

(주의: `insertTestAccount`의 SQL은 챕터 2 완료 시점 기준이라 `version` 컬럼이 없다. 챕터 5에서 `Account`에 `@Version`이 추가되며 이 INSERT문이 깨져서 `version` 컬럼을 추가하는 수정이 있었음 — [`LOG005`](LOG005-chapter5.md) 참고.)

**`build.gradle`의 `test` 태스크**
```groovy
tasks.named('test') {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
    }
}
```

## 로그로 관찰
Gradle이 기본적으로 통과한 테스트의 표준출력을 콘솔에 안 보여줘서, `build.gradle`의 `test` 태스크에 `testLogging { showStandardStreams = true }`를 영구 추가 (앞으로 모든 동시성 챕터에서 필요). 테스트에는 `@Slf4j` + `log.info(...)`로 각 단계를 기록 — Spring Boot 기본 로그 포맷에 스레드 이름(`[pool-N-thread-M]`)이 찍혀서 어느 스레드가 언제 뭘 했는지 바로 보임.

**결과**:
```
[REPEATABLE_READ] [A] 첫 번째 읽기: 10000.00 → [B] 업데이트 커밋 완료 → [A] 두 번째 읽기: 10000.00
[READ_COMMITTED]  [A] 첫 번째 읽기: 10000.00 → [B] 업데이트 커밋 완료 → [A] 두 번째 읽기: 5000.00
```
`READ_COMMITTED`에서만 값이 바뀜(non-repeatable read), `REPEATABLE_READ`는 트랜잭션 시작 시점 스냅샷을 계속 봐서 값이 그대로임을 확인.

## 시행착오
- 코드를 직접 타이핑하면서 `insertTestAccount("bob", "1000")`을 `"10000"`(원본 지시) 대신 오타로 입력 → `expected: 10000 but was: 1000.00` 실패. 격리수준 문제가 아니라 단순 오타였음을 코드 diff로 확인 후 수정.
- `owner` 컬럼이 있는지 헷갈렸던 질문 → 실제로 앱을 짧게 `bootRun`해서 `psql \d account`로 실제 스키마(`owner_name`, `balance numeric(38,2)`, `id`)를 직접 확인해 답함.

## 완료 체크리스트
- [x] `IsolationLevelTest` 작성 — 순수 JDBC로 두 트랜잭션 교차 실행
- [x] `READ_COMMITTED`에서 non-repeatable read 재현 확인
- [x] `REPEATABLE_READ`에서 방지되는 것 확인
- [x] `testLogging.showStandardStreams` 활성화 + `@Slf4j` 로그로 스레드 인터리빙 관찰
- [x] 이 로그 문서 작성

## ADR

**Decision**
- 격리수준 실험은 JPA/Hibernate를 거치지 않고 순수 JDBC(`DataSource` 직접 사용)로 진행.
- 여러 격리수준을 비교하는 테스트는 공통 실험 로직을 헬퍼 메서드로 추출해 재사용.
- `BigDecimal` 검증은 AssertJ `isEqualByComparingTo()`로 통일 (JUnit 기본 assertion 대신).
- Gradle `test` 태스크에 `testLogging.showStandardStreams = true`를 영구 설정.

**Drivers**
- JPA 1차 캐시가 순수 DB 격리수준 동작을 가릴 수 있어, 이번 챕터의 학습 목표(격리수준 자체)를 정확히 보려면 JDBC 레벨이 필요함.
- 앞으로 나올 동시성 챕터들(락, 데드락, 커넥션 풀)도 전부 스레드 인터리빙을 로그로 봐야 하므로 로깅 설정은 일회성이 아니라 영구적으로 필요.

**Alternatives considered**
- JPA/Hibernate로 실험 — 기각: 1차 캐시가 두 번째 `findById()`를 캐시로 반환해 격리수준 차이를 못 봄. (이 캐시 이슈 자체는 나중에 별도로 다룰 가치가 있는 주제.)
- 테스트마다 로그 보려고 `--info` 플래그를 매번 붙이는 방식 — 기각: 앞으로 계속 필요하므로 `build.gradle`에 영구 설정하는 게 더 낫다고 판단.

**Consequences**
- `build.gradle`의 `test` 태스크 로그 설정으로, 통과한 테스트도 콘솔 출력이 항상 보임 (테스트 로그가 많아지면 콘솔이 시끄러워질 수 있음 — 필요하면 나중에 실패한 테스트만 보이게 조정 가능).
- 앞으로 동시성 테스트를 짤 때 이번 챕터의 `CountDownLatch` 기반 스레드 교차 패턴과 `@Slf4j` 로깅 패턴을 재사용한다.

**Follow-ups**
- JPA 1차 캐시가 격리수준 인식을 가리는 문제는 별도 챕터(또는 부록)로 다뤄볼 가치 있음.
- `SERIALIZABLE` 격리수준(및 Postgres의 SSI 직렬화 실패 재시도)은 아직 다루지 않음 — 필요시 이 챕터에 이어서 추가하거나 별도 챕터로 분리.
