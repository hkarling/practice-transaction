package io.hkarling.transaction.app.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.hkarling.transaction.AbstractIntegrationTest;
import io.hkarling.transaction.domain.OutboxEvent;
import io.hkarling.transaction.domain.OutboxStatus;
import io.hkarling.transaction.infra.OutboxEventRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class OutboxRelayTest extends AbstractIntegrationTest {

  @Autowired
  private OutboxRelay outboxRelay;

  @Autowired
  private OutboxEventRepository outboxEventRepository;

  @Autowired
  private FakeMessageBroker messageBroker;

  @Test
  @DisplayName("한 이벤트 발행이 실패해도 다른 이벤트는 영향받지 않아야 한다")
  void oneFailureShouldNotBlockOtherEvents() {
    OutboxEvent event1 = outboxEventRepository.save(new OutboxEvent(1L, 2L, new BigDecimal("1000")));
    OutboxEvent event2 = outboxEventRepository.save(new OutboxEvent(3L, 4L, new BigDecimal("2000")));
    messageBroker.failFor(event1.getId());

    outboxRelay.relayPendingEvents();

    OutboxEvent reloadedEvent2 = outboxEventRepository.findById(event2.getId()).orElseThrow();
    assertThat(reloadedEvent2.getStatus()).isEqualTo(OutboxStatus.SENT);
  }

}