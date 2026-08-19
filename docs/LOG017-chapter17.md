# LOG017 — 챕터 17: 커넥션 풀 고갈 시뮬레이션 (HikariCP)

전체 진행도: [`README.md`](../README.md)

## 성격
챕터 16처럼 "버그 재현→수정"보다는 운영 중 실패 모드를 직접 만들어보고 관찰하는 실습.

## 목표
HikariCP 풀 크기보다 많은 동시 요청이 몰리고 각 요청이 커넥션을 오래 붙잡고 있으면, 나머지 요청은 커넥션을 기다리다 타임아웃된다. 작은 풀로 일부러 재현하고, 왜 이런 일이 생기는지 + 어떻게 완화하는지 확인.

## 시작 전 짚었던 것
- 챕터 5에서 모든 테스트 클래스가 Spring 컨텍스트를 공유하도록 만들었기 때문에(`AbstractIntegrationTest`), `application.yaml`의 HikariCP 설정 자체를 바꾸면 다른 모든 테스트(10개 스레드 동시 실행하는 챕터 4~7 테스트들)에 영향을 준다.
- 해결: 이 테스트 클래스에서만 `@DynamicPropertySource`를 추가로 선언해 풀 크기를 오버라이드. Spring은 설정이 다른 테스트를 별도 컨텍스트로 자동 분리해주므로, 이 테스트만 별도의 작은 풀을 가진 컨텍스트를 새로 띄우고 다른 테스트들의 공유 컨텍스트는 그대로 유지됨 — 실제로 `HikariPool-2`가 별도 생성되는 걸로 확인됨.

## 핵심 코드

**`test/app/monitoring/ConnectionPoolExhaustionTest.java`**
```java
@Slf4j
@SpringBootTest
class ConnectionPoolExhaustionTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void hikariProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    registry.add("spring.datasource.hikari.connection-timeout", () -> "2000");
  }

  @Autowired
  private AccountRepository accountRepository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Test
  @DisplayName("커넥션 풀 크기보다 많은 동시 요청이 오면 초과 요청은 타임아웃된다")
  void exceedingPoolSizeCausesTimeout() throws Exception {
    Account account = accountRepository.save(new Account("alice", new BigDecimal("10000")));
    int concurrentRequests = 4;

    List<Exception> failures = new CopyOnWriteArrayList<>();
    AtomicInteger successCount = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(concurrentRequests);
    TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

    for (int i = 0; i < concurrentRequests; i++) {
      pool.submit(() -> {
        try {
          txTemplate.executeWithoutResult(status -> {
            accountRepository.findById(account.getId());
            try {
              Thread.sleep(3000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
          successCount.incrementAndGet();
        } catch (Exception e) {
          failures.add(e);
        }
      });
    }

    pool.shutdown();
    pool.awaitTermination(20, TimeUnit.SECONDS);

    assertThat(failures).isNotEmpty();
  }

  @Test
  @DisplayName("풀 크기가 같아도 트랜잭션 보유 시간을 줄이면 고갈되지 않는다")
  void shortTransactionsAvoidExhaustionEvenWithSmallPool() throws Exception {
    Account account = accountRepository.save(new Account("bob", new BigDecimal("10000")));
    int concurrentRequests = 4;

    List<Exception> failures = new CopyOnWriteArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(concurrentRequests);
    TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

    for (int i = 0; i < concurrentRequests; i++) {
      pool.submit(() -> {
        try {
          txTemplate.executeWithoutResult(status -> {
            accountRepository.findById(account.getId());
            try {
              Thread.sleep(100);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
        } catch (Exception e) {
          failures.add(e);
        }
      });
    }

    pool.shutdown();
    pool.awaitTermination(20, TimeUnit.SECONDS);

    assertThat(failures).isEmpty();
  }

}
```

**설계**: 풀 크기 `2`, 커넥션 타임아웃 `2초`. 4개 요청이 동시에 커넥션을 요구. 첫 테스트는 각자 `3초`씩 붙잡아 초과 요청이 타임아웃되게(`3초 > 2초`), 두 번째 테스트는 `100ms`만 붙잡아 같은 풀 크기로도 전부 성공하게 설계.

## 결과
- **긴 보유 시간(3초, 풀 크기 2, 요청 4개)**: 성공 2, 실패 2 — 예외 타입은 예상했던 `SQLTransientConnectionException`이 아니라 **`CannotCreateTransactionException`**(원인: `Could not open JPA EntityManager for transaction`). HikariCP가 커넥션을 못 내주자 JPA `EntityManager`를 여는 단계에서 실패했고, Spring의 `JpaTransactionManager`가 이를 자기 트랜잭션 계층 예외로 감싼 것.
- **짧은 보유 시간(100ms, 풀 크기 동일하게 2, 요청 4개)**: 실패 0 — 같은 풀 크기인데도 각 트랜잭션이 커넥션을 금방 반납하니 대기 시간이 타임아웃보다 훨씬 짧아 전부 성공.

## 실무 교훈 — 풀을 늘리기 vs 트랜잭션을 짧게 하기
커넥션 풀 고갈을 보면 반사적으로 "풀 크기를 늘리자"고 생각하기 쉽지만, DB 서버 자체의 최대 커넥션 수는 유한하고(여러 애플리케이션 인스턴스가 공유하는 자원) 무한정 늘릴 수 없다. **더 근본적인 해법은 각 트랜잭션이 커넥션을 붙잡는 시간을 줄이는 것** — 트랜잭션 안에서 느린 외부 API 호출을 하거나, 불필요하게 긴 락을 잡거나, 필요 이상으로 큰 작업 단위를 하나의 트랜잭션으로 묶는 게 실제 원인인 경우가 많다.

## 완료 체크리스트
- [x] 작은 풀 크기 + 긴 보유 시간으로 커넥션 고갈 재현
- [x] 예외 타입 확인 (`CannotCreateTransactionException`)
- [x] 같은 풀 크기 + 짧은 보유 시간으로 고갈 방지 확인
- [x] `@DynamicPropertySource`로 특정 테스트만 별도 컨텍스트/풀 설정 분리하는 방법 확인
- [x] 이 로그 문서 작성

## ADR

**Decision**
- 커넥션 풀 설정을 테스트별로 다르게 하려면, 전역 `application.yaml`을 바꾸지 않고 `@DynamicPropertySource`를 해당 테스트 클래스에 추가하는 방식을 쓴다.
- 커넥션 풀 고갈 문제의 1차 대응은 풀 크기 증설이 아니라 트랜잭션 보유 시간 단축으로 접근한다.

**Drivers**
- 챕터 5의 컨텍스트 공유 최적화를 해치지 않으면서 특정 테스트만 다른 설정을 쓸 방법이 필요했음 — Spring의 컨텍스트 캐싱이 설정 차이를 자동으로 감지해 별도 컨텍스트를 만들어준다는 걸 활용.
- 풀 크기 증설은 임시방편이고, DB 서버의 총 커넥션 한도라는 진짜 제약이 있으므로 트랜잭션 설계 자체를 개선하는 게 근본 해법.

**Alternatives considered**
- `application.yaml`에서 전역 풀 크기를 줄여서 테스트 — 기각: 챕터 4~7의 10-스레드 동시성 테스트들이 공유 컨텍스트에서 커넥션 부족으로 실패할 위험.

**Consequences**
- 이 테스트 클래스는 별도의 `HikariPool-2` 컨텍스트를 새로 띄우므로, 다른 테스트들보다 약간의 추가 기동 비용이 있음 — 챕터 5에서 얻은 컨텍스트 캐싱 이점이 이 클래스에는 적용 안 됨 (의도된 트레이드오프).

**Follow-ups**
- 챕터 16의 `LockMonitor`처럼, `pg_stat_activity`로 실제 활성 커넥션 수를 관찰하는 도구를 추가하면 이 챕터와 연계해서 더 풍부하게 진단할 수 있음.
- 챕터 18(동시성 테스트 자동화)에서 지금까지 손으로 짜온 `CountDownLatch`/`TransactionTemplate` 패턴들을 재사용 가능한 형태로 정리.
