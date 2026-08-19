package io.hkarling.transaction.app.outbox;

import io.hkarling.transaction.app.transfer.TransferService;
import io.hkarling.transaction.domain.OutboxEvent;
import io.hkarling.transaction.infra.OutboxEventRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class TransferWithOutboxService {

  private final TransferService transferService;
  private final OutboxEventRepository outboxEventRepository;

  @Transactional
  public void transferAndRecordEvent(Long fromId, Long toId, BigDecimal amount) {
    transferService.transfer(fromId, toId, amount);
    outboxEventRepository.save(new OutboxEvent(fromId, toId, amount));
  }

}
