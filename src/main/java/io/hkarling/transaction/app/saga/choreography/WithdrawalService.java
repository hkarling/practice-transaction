package io.hkarling.transaction.app.saga.choreography;

import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class WithdrawalService {

  private final AccountRepository accountRepository;
  private final ApplicationEventPublisher eventPublisher;

  @Transactional
  public void withdraw(String sagaId, Long fromId, Long toId, BigDecimal amount) {
    Account from = accountRepository.findById(fromId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + fromId));
    from.withdraw(amount);
    accountRepository.save(from);

    eventPublisher.publishEvent(new WithdrawnEvent(sagaId, fromId, toId, amount));
  }

}
