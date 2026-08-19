# LOG016 — 챕터 16: 락 경합 모니터링

전체 진행도: [`README.md`](../README.md)

## 성격
"버그 재현→수정"이 아니라 **관찰 도구를 만들고, 챕터 6에서 만든 락 경합 상황에 실제로 적용해보는** 실습.

## 목표
챕터 6에서 데드락/락 대기를 재현할 때 "지금 어떤 트랜잭션이 어떤 락을 잡고 있고 누가 기다리는지"를 PostgreSQL 시스템 카탈로그(`pg_locks`, `pg_stat_activity`)로 직접 조회하는 도구를 만든다.

## 핵심 코드

**`app/monitoring/BlockedSessionInfo.java`**
```java
public record BlockedSessionInfo(
    Integer blockedPid,
    String blockedUser,
    Integer blockingPid,
    String blockingUser,
    String blockedStatement,
    String currentStatementInBlockingProcess
) {
}
```

**`app/monitoring/LockMonitor.java`** — PostgreSQL 공식 위키(Lock Monitoring)의 검증된 쿼리를 그대로 사용
```java
@RequiredArgsConstructor
@Component
public class LockMonitor {

  private static final String BLOCKING_QUERY = """
      SELECT 
          blocked_locks.pid          AS blocked_pid,
          blocked_activity.usename   AS blocked_user,
          blocking_locks.pid         AS blocking_pid,
          blocking_activity.usename  AS blocking_user,
          blocked_activity.query     AS blocked_statement,
          blocking_activity.query    AS current_statement_in_blocking_process
      FROM pg_catalog.pg_locks blocked_locks
      JOIN pg_catalog.pg_stat_activity blocked_activity 
          ON blocked_activity.pid = blocked_locks.pid
      JOIN pg_catalog.pg_locks blocking_locks 
          ON blocking_locks.locktype = blocked_locks.locktype
         AND blocking_locks.database      IS NOT DISTINCT FROM blocked_locks.database
         AND blocking_locks.relation      IS NOT DISTINCT FROM blocked_locks.relation
         AND blocking_locks.page          IS NOT DISTINCT FROM blocked_locks.page
         AND blocking_locks.tuple         IS NOT DISTINCT FROM blocked_locks.tuple
         AND blocking_locks.virtualxid    IS NOT DISTINCT FROM blocked_locks.virtualxid
         AND blocking_locks.transactionid IS NOT DISTINCT FROM blocked_locks.transactionid
         AND blocking_locks.classid       IS NOT DISTINCT FROM blocked_locks.classid
         AND blocking_locks.objid         IS NOT DISTINCT FROM blocked_locks.objid
         AND blocking_locks.objsubid      IS NOT DISTINCT FROM blocked_locks.objsubid
         AND blocking_locks.pid != blocked_locks.pid
      JOIN pg_catalog.pg_stat_activity blocking_activity 
          ON blocking_activity.pid = blocking_locks.pid
      WHERE NOT blocked_locks.granted;
      """;

  private final JdbcTemplate jdbcTemplate;

  public List<BlockedSessionInfo> findBlockedSessions() {
    return jdbcTemplate.query(BLOCKING_QUERY, (rs, rowNum) -> new BlockedSessionInfo(
        rs.getInt("blocked_pid"),
        rs.getString("blocked_user"),
        rs.getInt("blocking_pid"),
        rs.getString("blocking_user"),
        rs.getString("blocked_statement"),
        rs.getString("current_statement_in_blocking_process")
    ));
  }
}
```
쿼리를 직접 짜지 않고 [PostgreSQL 공식 위키(Lock Monitoring)](https://wiki.postgresql.org/wiki/Lock_Monitoring)의 검증된 쿼리를 그대로 가져옴 — `pg_locks` 자기 조인 조건(`locktype`/`database`/`relation`/`page`/`tuple`/`virtualxid`/`transactionid`/`classid`/`objid`/`objsubid`)이 복잡해서 짐작으로 작성하면 놓치기 쉬움.

**`test/app/monitoring/LockMonitorTest.java`** — 챕터 6의 `TransactionTemplate` + `CountDownLatch` 패턴 재사용
```java
@Slf4j
@SpringBootTest
class LockMonitorTest extends AbstractIntegrationTest {

  @Autowired
  private AccountRepository accountRepository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Autowired
  private LockMonitor lockMonitor;

  @Test
  @DisplayName("한 트랜잭션이 락을 쥐고 있으면 대기 중인 세션이 관찰된다")
  void observeBlockedSessionWhileLockIsHeld() throws Exception {
    Account account = accountRepository.save(new Account("alice", new BigDecimal("10000")));

    CountDownLatch lockAcquired = new CountDownLatch(1);
    CountDownLatch releaseLock = new CountDownLatch(1);
    TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

    ExecutorService pool = Executors.newFixedThreadPool(2);

    pool.submit(() -> txTemplate.executeWithoutResult(status -> {
      accountRepository.findByIdForUpdate(account.getId());
      lockAcquired.countDown();
      try {
        releaseLock.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }));

    pool.submit(() -> {
      try {
        lockAcquired.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      txTemplate.executeWithoutResult(status -> accountRepository.findByIdForUpdate(account.getId()));
    });

    lockAcquired.await(5, TimeUnit.SECONDS);
    Thread.sleep(500);

    List<BlockedSessionInfo> blockedSessions = lockMonitor.findBlockedSessions();
    blockedSessions.forEach(session -> log.info("블로킹 관찰: {}", session));

    releaseLock.countDown();
    pool.shutdown();
    pool.awaitTermination(15, TimeUnit.SECONDS);

    assertThat(blockedSessions).isNotEmpty();
  }
}
```

## 시행착오 — T1을 트랜잭션으로 안 감쌌던 실수
처음 작성한 코드에서 T1(락을 쥐는 쪽)의 `accountRepository.findByIdForUpdate(...)` 호출을 `txTemplate.executeWithoutResult(...)`로 감싸지 않고 그냥 직접 호출함. Spring Data JPA 쿼리 메서드는 자체적으로 자기만의 트랜잭션을 갖기 때문에, 감싸지 않으면 메서드가 리턴하는 즉시 그 자체 트랜잭션이 커밋되고 **락도 바로 풀려버림**:
1. T1: 락 획득 → 메서드 종료 → 즉시 커밋 + 락 해제
2. `lockAcquired.countDown()` — 이땐 이미 락이 없는 상태
3. T2가 나중에 잠그려 해도 이미 풀린 락이라 전혀 블로킹 안 되고 바로 성공

그래서 `findBlockedSessions()`가 빈 리스트를 반환했음 — **도구 자체는 정확히 동작했지만, 관찰할 "블로킹 상황" 자체가 안 만들어졌던 것.** T1도 T2와 동일하게 `txTemplate.executeWithoutResult(...)`로 감싸서 해결.

## 결과
```
블로킹 관찰: BlockedSessionInfo[blockedPid=68, blockedUser=test, blockingPid=67, blockingUser=test,
  blockedStatement=select ... from account a1_0 where a1_0.id=$1 for no key update of a1_0,
  currentStatementInBlockingProcess=select ... from account a1_0 where a1_0.id=$1 for no key update of a1_0]
```
`blockedPid=68`이 `blockingPid=67`을 기다리는 게 정확히 관찰됨. 두 세션 다 챕터 6에서 확인했던 `FOR NO KEY UPDATE` 쿼리(Hibernate가 `PESSIMISTIC_WRITE`를 변환한 형태)를 실행 중인 것도 재확인.

## 완료 체크리스트
- [x] `BlockedSessionInfo`/`LockMonitor` 작성 (공식 위키 쿼리 그대로 사용)
- [x] 챕터 6 패턴 재사용한 `LockMonitorTest` 작성
- [x] T1 트랜잭션 누락 버그 발견 및 수정
- [x] 실제 블로킹 관계(pid 단위) 관찰 성공
- [x] 이 로그 문서 작성

## ADR

**Decision**
- 락 경합 조회 쿼리는 직접 작성하지 않고 PostgreSQL 공식 위키의 검증된 쿼리를 그대로 사용한다.
- 관찰 테스트는 챕터 6에서 이미 검증한 `TransactionTemplate` + `CountDownLatch` 동시성 제어 패턴을 재사용한다.

**Drivers**
- `pg_locks` 자기 조인 조건이 10개 컬럼을 정확히 매칭해야 해서, 직접 작성하면 실수하기 쉬운 영역 — 검증된 소스를 쓰는 게 안전.
- 이미 챕터 6에서 신뢰성이 검증된 동시성 테스트 패턴을 재사용하는 게 새로 설계하는 것보다 안전하고 빠름.

**Alternatives considered**
- `pg_blocking_pids(pid)` 함수(Postgres 9.6+) 기반의 더 간단한 쿼리 — 기각(이번엔): 위키의 완전한 조인 쿼리가 blocked/blocking 양쪽의 상세 정보(쿼리 내용, 사용자 등)를 한 번에 주는 게 이번 학습 목적(무슨 일이 일어나는지 자세히 보기)에 더 맞다고 판단. 더 가벼운 조회가 필요하면 추후 `pg_blocking_pids` 기반으로 교체 가능.

**Consequences**
- Spring Data JPA 쿼리 메서드를 명시적 트랜잭션 없이 호출하면 즉시 커밋된다는 걸 다시 한번 실전에서 확인 — 동시성 테스트를 짤 때 항상 첫 번째로 의심해볼 지점으로 기억.
- `LockMonitor`는 이후 챕터(17 커넥션 풀 고갈, 18 동시성 테스트 자동화)에서도 진단 도구로 재사용 가능.

**Follow-ups**
- 챕터 17(커넥션 풀 고갈)에서 `pg_stat_activity`를 활용해 커넥션 사용 현황도 함께 살펴볼 수 있음.
