package io.hkarling.transaction.app;

import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class TransferRetryService {

  private static final int MAX_RETRY = 20;

  private final TransferService transferService;

  public void transferWithRetry(Long fromId, Long toId, BigDecimal amount) {
    for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
      try {
        transferService.transfer(fromId, toId, amount);
        return;
      } catch (ObjectOptimisticLockingFailureException e) {
        log.info("낙관적 락 충돌, 재시도 {}/{}", attempt, MAX_RETRY);
      }
    }
    throw new IllegalStateException("재시도 " + MAX_RETRY + "회 초과");
  }

}
