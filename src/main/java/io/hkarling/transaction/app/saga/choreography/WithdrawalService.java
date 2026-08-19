package io.hkarling.transaction.app.saga.choreography;

import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onDepositFailed(DepositFailedEvent event) {
    Account from = accountRepository.findById(event.fromId())
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + event.fromId()));
    from.deposit(event.amount());
    accountRepository.save(from);
  }
  
}
