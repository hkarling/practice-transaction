package io.hkarling.transaction.app.audit;

import io.hkarling.transaction.domain.AuditLog;
import io.hkarling.transaction.infra.AuditLogRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class AuditLogService {

  private final AuditLogRepository auditLogRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void log(String action, Long fromId, Long toId, BigDecimal amount, boolean success, String message) {
    AuditLog auditLog = new AuditLog(action, fromId, toId, amount, success, message);
    auditLogRepository.save(auditLog);
  }

}
