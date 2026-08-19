package io.hkarling.transaction.app.saga.orchestration;

import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class MultiStepTransferSagaOrchestrator {

  private static final BigDecimal FEE = new BigDecimal("100");

  private final OrchestratedDepositService depositService;
  private final OrchestratedFeeService feeService;
  private final OrchestratedWithdrawalService withdrawalService;

  public void executeTransfer(Long fromId, Long toId, Long feeAccountId, BigDecimal amount) {
    try {
      feeService.chargeFee(fromId, feeAccountId, FEE);
      withdrawalService.withdraw(fromId, amount);
      depositService.deposit(toId, amount);
    } catch (Exception e) {
      feeService.compensate(fromId, feeAccountId, FEE);
      withdrawalService.compensate(fromId, amount);
      throw e;
    }
  }
}
