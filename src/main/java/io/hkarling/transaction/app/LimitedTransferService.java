package io.hkarling.transaction.app;

import io.hkarling.transaction.domain.Account;
import io.hkarling.transaction.domain.AccountDailyUsage;
import io.hkarling.transaction.infra.AccountDailyUsageRepository;
import io.hkarling.transaction.infra.AccountRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class LimitedTransferService {

  public static final BigDecimal DAILY_LIMIT = BigDecimal.valueOf(5000);

  private final AccountRepository accountRepository;
  private final AccountDailyUsageRepository dailyUsageRepository;

  @Transactional
  public void transfer(Long fromId, Long toId, BigDecimal amount) {
    AccountDailyUsage usage = dailyUsageRepository.findByAccountIdAndUsageDateForUpdate(fromId, LocalDate.now())
        .orElseThrow(() -> new IllegalStateException("오늘 사용량 레코드 없음: " + fromId));
    usage.addUsage(amount, DAILY_LIMIT);

    Account from = accountRepository.findByIdForUpdate(fromId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + fromId));
    from.withdraw(amount);
    accountRepository.save(from);

    Account to = accountRepository.findByIdForUpdate(toId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + toId));
    to.deposit(amount);
    accountRepository.save(to);
  }

}
