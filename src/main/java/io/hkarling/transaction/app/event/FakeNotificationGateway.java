package io.hkarling.transaction.app.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

@Component
public class FakeNotificationGateway {

  private final List<TransferCompletedEvent> sentNotifications = new CopyOnWriteArrayList<>();

  public void send(TransferCompletedEvent event) {
    sentNotifications.add(event);
  }

  public List<TransferCompletedEvent> getSentNotifications() {
    return sentNotifications;
  }

}
