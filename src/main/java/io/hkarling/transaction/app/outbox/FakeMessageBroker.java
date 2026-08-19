package io.hkarling.transaction.app.outbox;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

@Component
public class FakeMessageBroker {

  private final List<Long> publishedEventIds = new CopyOnWriteArrayList<>();
  private final Set<Long> failingEventIds = ConcurrentHashMap.newKeySet();

  public void failFor(Long eventId) {
    failingEventIds.add(eventId);
  }

  public void publish(Long eventId, String message) {
    if (failingEventIds.contains(eventId)) {
      throw new IllegalStateException("메시지 브로커 연결 실패 (시뮬레이션): eventId=" + eventId);
    }
    publishedEventIds.add(eventId);
  }

  public List<Long> getPublishedEventIds() {
    return publishedEventIds;
  }
}
