package io.hkarling.transaction.app.idempotency;

import io.hkarling.transaction.app.transfer.TransferService;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class IdempotentTransferService {

  private final TransferService transferService;

  public void transfer(String idempotencyKey, Long fromId, Long toId, BigDecimal amount) {
    transferService.transfer(fromId, toId, amount);
  }

}
