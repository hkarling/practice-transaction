# LOG018 — 챕터 18: 동시성 테스트 자동화 (마지막 챕터)

전체 진행도: [`README.md`](../README.md)

## 목표
챕터 4~17에서 계속 반복해온 `CountDownLatch`+`ExecutorService` 보일러플레이트를 재사용 가능한 유틸리티로 뽑아내고, 반복 실행으로 결과 신뢰성을 높인다.

## 시작 전 짚었던 것 — "자동화"라는 표현에 대한 재검토
논의 끝에, 이 챕터 제목이 실제 내용보다 다소 거창하게 들린다는 걸 정직하게 인정하고 넘어감:
- **동시성 테스트 자동화 자체는 챕터 4부터 이미 하고 있었다.** `CountDownLatch`로 여러 스레드를 동시에 출발시키는 것 자체가 "사람이 손으로 여러 터미널을 열어 동시에 버튼을 누르는" 과정을 코드로 자동화한 것.
- 이 챕터가 실제로 더할 수 있는 건 두 가지뿐: **(1) 보일러플레이트 제거**(재사용성 개선에 가까움) **(2) 반복 실행으로 신뢰성 검증**(`@RepeatedTest`).
- `@RepeatedTest`는 동시성 전용 도구가 아니라 JUnit5의 범용 기능(무작위성·타이밍에 민감한 모든 테스트에 씀) — 동시성 검증에 재활용하는 것뿐. 더 rigorous한 동시성 전용 도구(OpenJDK jcstress 등)도 있지만 이 프로젝트 규모엔 과함.

## 핵심 코드

**`test/support/ConcurrentExecutionResult.java`**
```java
public record ConcurrentExecutionResult(int successCount, List<Exception> failures) {
}
```

**`test/support/ConcurrentExecutionRunner.java`**
```java
public class ConcurrentExecutionRunner {

  public static ConcurrentExecutionResult runConcurrently(int threadCount, Runnable action) throws InterruptedException {
    CountDownLatch readyLatch = new CountDownLatch(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger();
    List<Exception> failures = new CopyOnWriteArrayList<>();

    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    for (int i = 0; i < threadCount; i++) {
      pool.submit(() -> {
        try {
          readyLatch.countDown();
          startLatch.await();
          action.run();
          successCount.incrementAndGet();
        } catch (Exception e) {
          failures.add(e);
        }
      });
    }

    readyLatch.await(5, TimeUnit.SECONDS);
    startLatch.countDown();
    pool.shutdown();
    pool.awaitTermination(30, TimeUnit.SECONDS);

    return new ConcurrentExecutionResult(successCount.get(), failures);
  }
}
```

**`test/app/transfer/TransferRetryServiceTest.java`** (리팩터링 + 반복 검증)
```java
@Slf4j
@SpringBootTest
class TransferRetryServiceTest extends AbstractIntegrationTest {

  @Autowired
  private TransferRetryService transferRetryService;

  @Autowired
  private AccountRepository accountRepository;

  @RepeatedTest(10)
  @DisplayName("재시도까지 더하면 동시 출금이 정확히, 낭비 없이 처리된다")
  void concurrentWithdrawalsAreHandledCorrectlyWithRetry() throws Exception {
    Account from = accountRepository.save(new Account("alice", new BigDecimal("10000")));
    Account to = accountRepository.save(new Account("bob", new BigDecimal("0")));
    BigDecimal withdrawAmount = new BigDecimal("2000");

    ConcurrentExecutionResult result = ConcurrentExecutionRunner.runConcurrently(10,
        () -> transferRetryService.transferWithRetry(from.getId(), to.getId(), withdrawAmount));

    Account reloaded = accountRepository.findById(from.getId()).orElseThrow();

    log.info("성공한 출금 수: {}, 실패한 출금 수: {}", result.successCount(), result.failures().size());
    log.info("최종 잔액: {}", reloaded.getBalance());
    result.failures().forEach(e -> log.info("실패 원인: {} - {}", e.getClass().getName(), e.getMessage()));

    assertThat(result.successCount()).isEqualTo(5);
    assertThat(result.failures()).hasSize(5);
    assertThat(reloaded.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
  }
}
```

## 결과
- 리팩터링 전/후 동작 동등성 확인 (여전히 정확히 5/5/0).
- `@RepeatedTest(10)`으로 10회 반복 실행, **매번** 동일하게 정확한 결과 확인 — `BUILD SUCCESSFUL`. 낙관적 락+재시도 구현이 타이밍에 상관없이 항상 올바르게 동작한다는 확신을 한 번의 실행보다 훨씬 강하게 얻음.

## 완료 체크리스트
- [x] `ConcurrentExecutionRunner`/`ConcurrentExecutionResult` 작성 (`support` 패키지 신설)
- [x] `TransferRetryServiceTest`를 유틸리티 사용하도록 리팩터링, 동작 동등성 확인
- [x] `@RepeatedTest(10)`으로 반복 검증 적용, 10회 전부 통과 확인
- [x] "자동화"라는 표현의 실제 의미/한계를 정직하게 재검토
- [x] 이 로그 문서 작성

## ADR

**Decision**
- 반복되는 N-스레드 동시성 테스트 보일러플레이트를 `io.hkarling.transaction.support.ConcurrentExecutionRunner`로 추출한다.
- 대표 테스트(`TransferRetryServiceTest`)에 `@RepeatedTest(10)`을 적용해 결과 안정성을 검증한다.
- CI 파이프라인 통합, jcstress 같은 전문 동시성 검증 도구 도입은 이 프로젝트 범위 밖으로 남긴다(개념만 인지).

**Drivers**
- 챕터 4~17에서 거의 동일한 스레드 관리 코드를 반복 작성하면서 실수(예: 챕터 16의 T1 트랜잭션 누락)가 실제로 발생했음 — 재사용 가능한 유틸리티가 이런 실수를 줄여줌.
- 동시성 버그는 타이밍 의존적이라 단발성 테스트 통과가 충분한 증거가 아니라는 걸 챕터 4에서부터 계속 겪어왔음 — 반복 검증으로 그 신뢰도를 보강.

**Alternatives considered**
- 모든 기존 동시성 테스트를 전부 리팩터링 — 기각(이번엔): 대표 사례 하나로 유틸리티의 유효성을 증명하는 것으로 충분, 나머지는 필요할 때 점진적으로 전환 가능.
- jcstress 등 전문 동시성 검증 도구 도입 — 기각: 이 프로젝트가 겪은 버그(Lost Update, 데드락 등)는 `@RepeatedTest` 수준의 반복 검증으로 충분히 잡히는 종류라 과한 인프라.

**Consequences**
- 앞으로 새 동시성 테스트를 짤 때 `ConcurrentExecutionRunner.runConcurrently(threadCount, action)` 한 줄로 시작 가능.
- `@RepeatedTest`를 쓴 테스트는 일반 테스트보다 실행 시간이 N배 걸림 — 반복 횟수는 신뢰도와 실행 시간의 트레이드오프로 상황에 맞게 조정.

**Follow-ups**
- 필요 시 다른 동시성 테스트들(`ConcurrentTransferTest`, `PessimisticTransferServiceTest`, `DeadlockTest` 등)도 점진적으로 `ConcurrentExecutionRunner`로 전환 가능.
- CI 파이프라인 구성(GitHub Actions 등)은 이 프로젝트 범위 밖 — 실제 배포 환경에서는 이 테스트들을 커밋/PR마다 자동 실행하도록 편입하는 게 정석.

---

## 커리큘럼 완주
Phase 1(단일 DB 트랜잭션 기초, 챕터 1~6) → Phase 2(복잡한 비즈니스 케이스, 챕터 7~10) → Phase 3(분산 트랜잭션, 챕터 11~15) → Phase 4(운영 관점, 챕터 16~18)까지 18개 챕터를 전부 "버그 있는 코드 → 테스트로 재현 → 수정 → 로그 기록"의 원칙대로 진행 완료. `docs/LOG000`~`LOG018`에 세팅부터 마지막 챕터까지의 전체 시행착오와 결정 기록이 남아있다.
