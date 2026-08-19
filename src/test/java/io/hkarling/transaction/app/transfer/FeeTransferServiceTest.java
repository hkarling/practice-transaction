package io.hkarling.transaction.app.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hkarling.transaction.AbstractIntegrationTest;
import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@Slf4j
@SpringBootTest
class FeeTransferServiceTest extends AbstractIntegrationTest {

  @Autowired
  private FeeTransferService feeTransferService;

  @Autowired
  private AccountRepository accountRepository;


  @Test
  @DisplayName("이체가 실패하면 수수료도 함께 롤백되어야 한다")
  void feeShouldRollBackWhenTransferFails() {
    Account from = accountRepository.save(new Account("alice", new BigDecimal("10000")));
    Account feeAccount = accountRepository.save(new Account("bank-fee", new BigDecimal("0")));
    Long invalidToId = 999_999L;

    assertThatThrownBy(
        () -> feeTransferService.transfer(from.getId(), invalidToId, feeAccount.getId(), new BigDecimal("3000")))
        .isInstanceOf(IllegalArgumentException.class);

    Account reloadedFrom = accountRepository.findById(from.getId()).orElseThrow();
    Account reloadedFeeAccount = accountRepository.findById(feeAccount.getId()).orElseThrow();

    log.info("이체 실패 후 from 잔액: {} (기대값: 10000)", reloadedFrom.getBalance());
    log.info("이체 실패 후 수수료 계좌 잔액: {} (기대값: 0)", reloadedFeeAccount.getBalance());
    
    assertThat(reloadedFrom.getBalance()).isEqualByComparingTo("10000");
    assertThat(reloadedFeeAccount.getBalance()).isEqualByComparingTo("0");
  }
}