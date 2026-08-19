package io.hkarling.transaction.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.hkarling.transaction.AbstractIntegrationTest;
import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.domain.AccountDailyUsage;
import io.hkarling.transaction.infra.AccountDailyUsageRepository;
import io.hkarling.transaction.infra.AccountRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
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
class LimitedTransferServiceTest extends AbstractIntegrationTest {

  @Autowired
  private LimitedTransferService limitedTransferService;

  @Autowired
  private AccountRepository accountRepository;

  @Autowired
  private AccountDailyUsageRepository dailyUsageRepository;

  @Test
  @DisplayName("락 없이 동시 이체하면 일일 한도를 넘길 수 있다")
  void concurrentTransfersCanExceedDailyLimit() throws Exception {
    Account from = accountRepository.save(new Account("alice", new BigDecimal("100000")));
    Account to = accountRepository.save(new Account("bob", new BigDecimal("0")));
    dailyUsageRepository.save(new AccountDailyUsage(from.getId(), LocalDate.now()));

    BigDecimal amount = new BigDecimal("1000");
    int threadCount = 10;

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
          limitedTransferService.transfer(from.getId(), to.getId(), amount);
          successCount.incrementAndGet();
        } catch (Exception e) {
          failures.add(e);
        }
      });
    }

    readyLatch.await(5, TimeUnit.SECONDS);
    startLatch.countDown();
    pool.shutdown();
    pool.awaitTermination(15, TimeUnit.SECONDS);

    AccountDailyUsage reloadedUsage = dailyUsageRepository
        .findByAccountIdAndUsageDate(from.getId(), LocalDate.now())
        .orElseThrow();

    log.info("성공한 이체 수: {}, 실패한 이체 수: {}", successCount.get(), failures.size());
    log.info("일일 한도: {}, 실제 사용량: {}", LimitedTransferService.DAILY_LIMIT, reloadedUsage.getUsedAmount());
    failures.forEach(e -> log.info("실패 원인: {} - {}", e.getClass().getName(), e.getMessage()));

    assertThat(successCount.get()).isEqualTo(5);
    assertThat(failures).hasSize(5);
    assertThat(reloadedUsage.getUsedAmount()).isEqualByComparingTo(LimitedTransferService.DAILY_LIMIT);
  }

}