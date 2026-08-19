package io.hkarling.transaction.app.event;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class TransferNotificationListener {

  private final FakeNotificationGateway notificationGateway;

  @EventListener
  public void handle(TransferCompletedEvent event) {
    notificationGateway.send(event);
  }

}
