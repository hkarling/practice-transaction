package io.hkarling.transaction.app.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hkarling.transaction.AbstractIntegrationTest;
import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import io.hkarling.transaction.infra.AuditLogRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AuditedTransferServiceTest extends AbstractIntegrationTest {

  @Autowired
  private AuditedTransferService auditedTransferService;

  @Autowired
  private AccountRepository accountRepository;

  @Autowired
  private AuditLogRepository auditLogRepository;

  @Test
  @DisplayName("이체가 실패해도 감사 로그는 남아야 한다")
  void auditLogShouldSurviveWhenTransferFails() {
    Account from = accountRepository.save(new Account("alice", new BigDecimal("10000")));
    Long invalidToId = 999_999L;

    assertThatThrownBy(() -> auditedTransferService.transfer(from.getId(), invalidToId, new BigDecimal("1000")))
        .isInstanceOf(IllegalArgumentException.class);

    long logCount = auditLogRepository.count();
    assertThat(logCount).isEqualTo(1);
  }

}