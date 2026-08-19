package io.hkarling.transaction.app.saga.orchestration;

import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class OrchestratedFeeService {

  private final AccountRepository accountRepository;

  @Transactional
  public void chargeFee(Long fromId, Long feeAccountId, BigDecimal fee) {
    Account from = accountRepository.findById(fromId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + fromId));
    from.withdraw(fee);
    accountRepository.save(from);

    Account feeAccount = accountRepository.findById(feeAccountId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + feeAccountId));
    feeAccount.deposit(fee);
    accountRepository.save(feeAccount);
  }

  @Transactional
  public void compensate(Long fromId, Long feeAccountId, BigDecimal fee) {
    Account feeAccount = accountRepository.findById(feeAccountId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + feeAccountId));
    feeAccount.withdraw(fee);
    accountRepository.save(feeAccount);

    Account from = accountRepository.findById(fromId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + fromId));
    from.deposit(fee);
    accountRepository.save(from);
  }

}
