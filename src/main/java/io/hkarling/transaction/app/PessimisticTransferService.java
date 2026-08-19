package io.hkarling.transaction.app;

import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class PessimisticTransferService {

  private final AccountRepository accountRepository;

  @Transactional
  public void transfer(Long fromId, Long toId, BigDecimal amount) {
    Account from = accountRepository.findByIdForUpdate(fromId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + fromId));
    from.withdraw(amount);
    accountRepository.save(from);
    Account to = accountRepository.findByIdForUpdate(toId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + toId));
    to.deposit(amount);
    accountRepository.save(to);
  }

}
