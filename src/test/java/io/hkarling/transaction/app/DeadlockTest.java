package io.hkarling.transaction.app;

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
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@SpringBootTest
class DeadlockTest extends AbstractIntegrationTest {

  @Autowired
  private AccountRepository accountRepository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Autowired
  private PessimisticTransferService pessimisticTransferService;

  @Test
  @DisplayName("서로 반대 순서로 락을 잡으면 데드락이 발생하고, 한쪽만 실패한다")
  void oppositeLockOrderCausesDeadlock() throws Exception {
    Account accountA = accountRepository.save(new Account("alice", new BigDecimal("10000")));
    Account accountB = accountRepository.save(new Account("bob", new BigDecimal("10000")));

    CountDownLatch firstLockAcquired = new CountDownLatch(2);
    List<Exception> failures = new CopyOnWriteArrayList<>();
    TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

    ExecutorService pool = Executors.newFixedThreadPool(2);

    pool.submit(() -> {
      try {
        txTemplate.executeWithoutResult(status -> {
          accountRepository.findByIdForUpdate(accountA.getId());
          firstLockAcquired.countDown();
          awaitQuietly(firstLockAcquired);
          accountRepository.findByIdForUpdate(accountB.getId());
        });
      } catch (Exception e) {
        log.info("[T1] 실패: {} - {}", e.getClass().getName(), e.getMessage());
        failures.add(e);
      }
    });

    pool.submit(() -> {
      try {
        txTemplate.executeWithoutResult(status -> {
          accountRepository.findByIdForUpdate(accountB.getId());
          firstLockAcquired.countDown();
          awaitQuietly(firstLockAcquired);
          accountRepository.findByIdForUpdate(accountA.getId());
        });
      } catch (Exception e) {
        log.info("[T2] 실패: {} - {}", e.getClass().getName(), e.getMessage());
        failures.add(e);
      }
    });

    pool.shutdown();
    pool.awaitTermination(15, TimeUnit.SECONDS);

    log.info("실패한 트랜잭션 수: {}", failures.size());
    assertThat(failures).hasSize(1);
  }

  private void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  @DisplayName("락 순서를 고정하면 반대 방향 동시 이체에서도 데드락이 안 난다")
  void consistentLockOrderPreventsDeadlock() throws Exception {
    Account accountA = accountRepository.save(new Account("alice", new BigDecimal("10000")));
    Account accountB = accountRepository.save(new Account("bob", new BigDecimal("10000")));

    List<Exception> failures = new CopyOnWriteArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch readyLatch = new CountDownLatch(2);
    CountDownLatch startLatch = new CountDownLatch(1);

    pool.submit(() -> {
      try {
        readyLatch.countDown();
        startLatch.await();
        pessimisticTransferService.transfer(accountA.getId(), accountB.getId(), new BigDecimal("1000"));
      } catch (Exception e) {
        failures.add(e);
      }
    });

    pool.submit(() -> {
      try {
        readyLatch.countDown();
        startLatch.await();
        pessimisticTransferService.transfer(accountB.getId(), accountA.getId(), new BigDecimal("1000"));
      } catch (Exception e) {
        failures.add(e);
      }
    });

    readyLatch.await(5, TimeUnit.SECONDS);
    startLatch.countDown();

    pool.shutdown();
    pool.awaitTermination(15, TimeUnit.SECONDS);

    log.info("실패한 트랜잭션 수: {}", failures.size());
    assertThat(failures).isEmpty();
  }

}
