package io.hkarling.transaction.app.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.hkarling.transaction.AbstractIntegrationTest;
import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class IdempotentTransferServiceTest extends AbstractIntegrationTest {

  @Autowired
  private IdempotentTransferService idempotentTransferService;

  @Autowired
  private AccountRepository accountRepository;

  @Test
  @DisplayName("같은 멱등성 키로 재시도해도 이체는 한 번만 처리되어야 한다")
  void duplicateRequestWithSameKeyShouldBeProcessedOnce() {
    Account from = accountRepository.save(new Account("alice", new BigDecimal("10000")));
    Account to = accountRepository.save(new Account("bob", new BigDecimal("0")));
    String idempotencyKey = UUID.randomUUID().toString();

    idempotentTransferService.transfer(idempotencyKey, from.getId(), to.getId(), new BigDecimal("3000"));
    idempotentTransferService.transfer(idempotencyKey, from.getId(), to.getId(), new BigDecimal("3000")); // 재시도 시뮬레이션

    Account reloaded = accountRepository.findById(from.getId()).orElseThrow();
    assertThat(reloaded.getBalance()).isEqualByComparingTo("7000");
  }

}