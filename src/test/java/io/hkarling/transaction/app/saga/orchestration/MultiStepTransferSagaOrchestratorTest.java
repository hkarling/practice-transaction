package io.hkarling.transaction.app.saga.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hkarling.transaction.AbstractIntegrationTest;
import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MultiStepTransferSagaOrchestratorTest extends AbstractIntegrationTest {

  @Autowired
  private MultiStepTransferSagaOrchestrator orchestrator;

  @Autowired
  private AccountRepository accountRepository;

  @Test
  @DisplayName("실패하지 않은 단계까지 보상하면 안 된다")
  void shouldNotCompensateStepsThatNeverSucceeded() {
    Account from = accountRepository.save(new Account("alice", new BigDecimal("950")));
    Account to = accountRepository.save(new Account("bob", new BigDecimal("0")));
    Account feeAccount = accountRepository.save(new Account("bank-fee", new BigDecimal("0")));

    assertThatThrownBy(
        () -> orchestrator.executeTransfer(from.getId(), to.getId(), feeAccount.getId(), new BigDecimal("3000")))
        .isInstanceOf(IllegalStateException.class);

    Account reloadedFrom = accountRepository.findById(from.getId()).orElseThrow();
    assertThat(reloadedFrom.getBalance()).isEqualByComparingTo("950");
  }

}