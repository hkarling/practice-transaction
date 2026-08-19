package io.hkarling.transaction.app.idempotency;

import io.hkarling.transaction.app.transfer.TransferService;
import io.hkarling.transaction.domain.IdempotencyKey;
import io.hkarling.transaction.infra.IdempotencyKeyRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class IdempotentTransferService {

  private final TransferService transferService;
  private final IdempotencyKeyRepository idempotencyKeyRepository;

  public void transfer(String idempotencyKey, Long fromId, Long toId, BigDecimal amount) {
    if (idempotencyKeyRepository.existsByRequestKey(idempotencyKey)) {
      return;
    }
    transferService.transfer(fromId, toId, amount);
    idempotencyKeyRepository.save(new IdempotencyKey(idempotencyKey));
  }

}
