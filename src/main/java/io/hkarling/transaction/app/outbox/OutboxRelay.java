package io.hkarling.transaction.app.outbox;

import io.hkarling.transaction.domain.OutboxEvent;
import io.hkarling.transaction.domain.OutboxStatus;
import io.hkarling.transaction.infra.OutboxEventRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class OutboxRelay {

  private final OutboxEventRepository outboxEventRepository;
  private final OutboxEventPublisher outboxEventPublisher;

  public void relayPendingEvents() {
    List<OutboxEvent> pending = outboxEventRepository.findByStatus(OutboxStatus.PENDING);
    for (OutboxEvent event : pending) {
      try {
        outboxEventPublisher.publish(event.getId());
      } catch (Exception e) {
        // 이 이벤트는 PENDING으로 남음 — 다음 릴레이 실행 때 재시도됨
      }
    }
  }

}
