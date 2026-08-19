package io.hkarling.transaction.app.outbox;

import io.hkarling.transaction.domain.OutboxEvent;
import io.hkarling.transaction.domain.OutboxStatus;
import io.hkarling.transaction.infra.OutboxEventRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class OutboxRelay {

  private final OutboxEventRepository outboxEventRepository;
  private final FakeMessageBroker messageBroker;

  @Transactional
  public void relayPendingEvents() {
    List<OutboxEvent> pending = outboxEventRepository.findByStatus(OutboxStatus.PENDING);
    for (OutboxEvent event : pending) {
      messageBroker.publish(event.getId(), "이체 완료: " + event.getFromId() + " -> " + event.getToId());
      event.markSent();
    }
  }

}
