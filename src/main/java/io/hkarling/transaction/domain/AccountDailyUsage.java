package io.hkarling.transaction.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
public class AccountDailyUsage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long accountId;

  private LocalDate usageDate;

  private BigDecimal usedAmount;

  public AccountDailyUsage(Long accountId, LocalDate usageDate) {
    this.accountId = accountId;
    this.usageDate = usageDate;
    this.usedAmount = BigDecimal.ZERO;
  }

  public void addUsage(BigDecimal amount, BigDecimal dailyLimit) {
    BigDecimal newTotal = usedAmount.add(amount);
    if (newTotal.compareTo(dailyLimit) > 0) {
      throw new IllegalStateException("일일 이체 한도 초과");
    }
    this.usedAmount = newTotal;
  }

}
