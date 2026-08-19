package io.hkarling.transaction.app.saga.orchestration;

import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class TransferSagaOrchestrator {

  private final OrchestratedWithdrawalService withdrawalService;
  private final OrchestratedDepositService depositService;

  public void executeTransfer(Long fromId, Long toId, BigDecimal amount) {
    withdrawalService.withdraw(fromId, amount);
    depositService.deposit(toId, amount);
  }

}
