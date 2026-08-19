package io.hkarling.transaction.app.transfer;

import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class FeeTransferService {

  public static final BigDecimal FEE = BigDecimal.valueOf(100);

  private final AccountRepository accountRepository;
  private final FeeService feeService;

  @Transactional
  public void transfer(Long fromId, Long toId, Long feeAccountId, BigDecimal amount) {
    feeService.chargeFee(fromId, feeAccountId, FEE);

    Account from = accountRepository.findById(fromId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + fromId));
    from.withdraw(amount);
    accountRepository.save(from);

    Account to = accountRepository.findById(toId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + toId));
    to.deposit(amount);
    accountRepository.save(to);
  }

}
