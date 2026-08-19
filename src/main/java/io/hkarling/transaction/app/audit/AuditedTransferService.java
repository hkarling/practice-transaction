package io.hkarling.transaction.app.audit;

import io.hkarling.transaction.app.transfer.TransferService;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class AuditedTransferService {

  private final TransferService transferService;
  private final AuditLogService auditLogService;

  @Transactional
  public void transfer(Long fromId, Long toId, BigDecimal amount) {
    try {
      transferService.transfer(fromId, toId, amount);
      auditLogService.log("TRANSFER", fromId, toId, amount, true, "성공");
    } catch (Exception e) {
      auditLogService.log("TRANSFER", fromId, toId, amount, false, e.getMessage());
      throw e;
    }
  }

}
