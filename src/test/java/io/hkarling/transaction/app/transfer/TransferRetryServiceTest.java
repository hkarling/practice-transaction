package io.hkarling.transaction.app.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import io.hkarling.transaction.AbstractIntegrationTest;
import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import io.hkarling.transaction.support.ConcurrentExecutionResult;
import io.hkarling.transaction.support.ConcurrentExecutionRunner;
import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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