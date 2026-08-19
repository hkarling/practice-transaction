package io.hkarling.transaction.app.transfer;

import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class FeeService {

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


}
