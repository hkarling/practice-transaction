package io.hkarling.transaction.app.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import io.hkarling.transaction.AbstractIntegrationTest;
import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@SpringBootTest
class ConnectionPoolExhaustionTest extends AbstractIntegrationTest {

  @Autowired
  private AccountRepository accountRepository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @DynamicPropertySource
  static void hikariProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    registry.add("spring.datasource.hikari.connection-timeout", () -> "2000");
  }

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

    log.info("성공: {}, 실패: {}", successCount.get(), failures.size());
    failures.forEach(e -> log.info("실패 원인: {} - {}", e.getClass().getName(), e.getMessage()));

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
              Thread.sleep(100); // 3000 -> 100으로 대폭 단축
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

    log.info("실패: {}", failures.size());
    assertThat(failures).isEmpty();
  }
  
}
