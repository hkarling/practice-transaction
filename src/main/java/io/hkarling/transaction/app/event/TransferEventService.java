package io.hkarling.transaction.app.event;

import io.hkarling.transaction.app.transfer.TransferService;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class TransferEventService {

  private final TransferService transferService;
  private final ApplicationEventPublisher eventPublisher;

  @Transactional
  public void transferAndNotify(Long fromId, Long toId, BigDecimal amount, boolean simulateFailureAfterEvent) {
    transferService.transfer(fromId, toId, amount);
    eventPublisher.publishEvent(new TransferCompletedEvent(fromId, toId, amount));

    if (simulateFailureAfterEvent) {
      throw new IllegalStateException("이후 처리 중 실패 (시뮬레이션)");
    }
  }

}
