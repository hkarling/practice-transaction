package io.hkarling.transaction.app.saga.orchestration;

import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class OrchestratedWithdrawalService {

  private final AccountRepository accountRepository;

  @Transactional
  public void withdraw(Long fromId, BigDecimal amount) {
    Account from = accountRepository.findById(fromId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + fromId));
    from.withdraw(amount);
    accountRepository.save(from);
  }

  @Transactional
  public void compensate(Long fromId, BigDecimal amount) {
    Account from = accountRepository.findById(fromId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + fromId));
    from.deposit(amount);
    accountRepository.save(from);
  }

}
