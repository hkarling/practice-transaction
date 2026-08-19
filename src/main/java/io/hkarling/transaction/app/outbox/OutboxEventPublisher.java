package io.hkarling.transaction.app.outbox;

import io.hkarling.transaction.domain.OutboxEvent;
import io.hkarling.transaction.infra.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class OutboxEventPublisher {

  private final OutboxEventRepository outboxEventRepository;
  private final FakeMessageBroker messageBroker;

  @Transactional
  public void publish(Long eventId) {
    OutboxEvent event = outboxEventRepository.findById(eventId)
        .orElseThrow(() -> new IllegalArgumentException("이벤트 없음: " + eventId));
    messageBroker.publish(event.getId(), "이체 완료: " + event.getFromId() + " -> " + event.getToId());
    event.markSent();
  }

}
