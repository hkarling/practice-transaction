package io.hkarling.transaction.app.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.hkarling.transaction.AbstractIntegrationTest;
import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TransferServiceTest extends AbstractIntegrationTest {

  @Autowired
  private TransferService transferService;

  @Autowired
  private AccountRepository accountRepository;

  @Test
  @DisplayName("이체 중간에 실패하면 출금액도 롤백되어야 한다")
  void withdrawalShouldRollbackWhenTransferFailsMidway() {
    Account from = accountRepository.save(new Account("alice", new BigDecimal("10000")));
    Long invalidToId = 999_999L;

    assertThrows(IllegalArgumentException.class,
        () -> transferService.transfer(from.getId(), invalidToId, new BigDecimal("3000")));

    Account reloaded = accountRepository.findById(from.getId()).orElseThrow();
    assertEquals(0, reloaded.getBalance().compareTo(new BigDecimal("10000")));
  }
}
