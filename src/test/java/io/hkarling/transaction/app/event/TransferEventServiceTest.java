package io.hkarling.transaction.app.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hkarling.transaction.AbstractIntegrationTest;
import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.infra.AccountRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TransferEventServiceTest extends AbstractIntegrationTest {

  @Autowired
  private TransferEventService transferEventService;

  @Autowired
  private AccountRepository accountRepository;

  @Autowired
  private FakeNotificationGateway notificationGateway;

  @Test
  @DisplayName("트랜잭션이 롤백돼도 이벤트 리스너가 즉시 실행되면 알림이 나가버린다")
  void notificationIsSentEvenWhenTransactionRollsBack() {
    Account from = accountRepository.save(new Account("alice", new BigDecimal("10000")));
    Account to = accountRepository.save(new Account("bob", new BigDecimal("0")));

    assertThatThrownBy(
        () -> transferEventService.transferAndNotify(from.getId(), to.getId(), new BigDecimal("3000"), true))
        .isInstanceOf(IllegalStateException.class);

    assertThat(notificationGateway.getSentNotifications()).isEmpty();
  }

  @Test
  @DisplayName("트랜잭션이 커밋되면 알림이 정상적으로 나간다")
  void notificationIsSentWhenTransactionCommits() {
    Account from = accountRepository.save(new Account("carol", new BigDecimal("10000")));
    Account to = accountRepository.save(new Account("dave", new BigDecimal("0")));
    int before = notificationGateway.getSentNotifications().size();

    transferEventService.transferAndNotify(from.getId(), to.getId(), new BigDecimal("1000"), false);

    assertThat(notificationGateway.getSentNotifications()).hasSize(before + 1);
  }

}