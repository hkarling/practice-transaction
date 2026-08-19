package io.hkarling.transaction.app.saga.choreography;

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
class WithdrawalServiceTest extends AbstractIntegrationTest {

  @Autowired
  private WithdrawalService withdrawalService;

  @Autowired
  private AccountRepository accountRepository;

  @Test
  @DisplayName("입금이 실패하면 출금도 보상(환불)되어야 한다")
  void withdrawalShouldBeCompensatedWhenDepositFails() {
    Account from = accountRepository.save(new Account("alice", new BigDecimal("10000")));
    Long invalidToId = 999_999L;

    withdrawalService.withdraw(UUID.randomUUID().toString(), from.getId(), invalidToId, new BigDecimal("3000"));

    Account reloaded = accountRepository.findById(from.getId()).orElseThrow();
    assertThat(reloaded.getBalance()).isEqualByComparingTo("10000");
  }

}