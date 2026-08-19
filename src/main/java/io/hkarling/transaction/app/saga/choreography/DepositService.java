package io.hkarling.transaction.app.saga.choreography;

import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@RequiredArgsConstructor
@Service
public class DepositService {

  private final AccountRepository accountRepository;
  private final ApplicationEventPublisher eventPublisher;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onWithdrawn(WithdrawnEvent event) {
    try {
      Account to = accountRepository.findById(event.toId())
          .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + event.toId()));
      to.deposit(event.amount());
      accountRepository.save(to);
    } catch (Exception e) {
      eventPublisher.publishEvent(
          new DepositFailedEvent(event.sagaId(), event.fromId(), event.toId(), event.amount(), e.getMessage()));
    }
  }

}
