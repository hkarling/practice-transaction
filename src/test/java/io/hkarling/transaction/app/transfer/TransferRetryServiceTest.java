package io.hkarling.transaction.app.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import io.hkarling.transaction.AbstractIntegrationTest;
import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@Slf4j
@SpringBootTest
class TransferRetryServiceTest extends AbstractIntegrationTest {

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