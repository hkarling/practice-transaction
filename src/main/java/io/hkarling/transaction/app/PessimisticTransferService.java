package io.hkarling.transaction.app;

import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class PessimisticTransferService {

  private final AccountRepository accountRepository;

  @Transactional
  public void transfer(Long fromId, Long toId, BigDecimal amount) {
    long lowerId = Math.min(fromId, toId);
    long higherId = Math.max(fromId, toId);

    Map<Long, Account> accounts = new HashMap<>();
    accounts.put(lowerId, accountRepository.findByIdForUpdate(lowerId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + lowerId)));
    accounts.put(higherId, accountRepository.findByIdForUpdate(higherId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + higherId)));

    Account from = accounts.get(fromId);
    Account to = accounts.get(toId);
    from.withdraw(amount);
    accountRepository.save(from);
    to.deposit(amount);
    accountRepository.save(to);
  }

}
