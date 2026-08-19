package io.hkarling.transaction.app.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import io.hkarling.transaction.AbstractIntegrationTest;
import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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