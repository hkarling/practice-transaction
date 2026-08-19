package io.hkarling.transaction.app.event;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@RequiredArgsConstructor
@Component
public class TransferNotificationListener {

  private final FakeNotificationGateway notificationGateway;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handle(TransferCompletedEvent event) {
    notificationGateway.send(event);
  }

}
