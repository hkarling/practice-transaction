package io.hkarling.transaction.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
public class Account {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String ownerName;

  private BigDecimal balance;

  @Version
  private Long version;

  public Account(String ownerName, BigDecimal balance) {
    this.ownerName = ownerName;
    this.balance = balance;
  }

  public void withdraw(BigDecimal amount) {
    if (balance.compareTo(amount) < 0) {
      throw new IllegalStateException("잔액 부족: " + ownerName);
    }
    balance = balance.subtract(amount);
  }

  public void deposit(BigDecimal amount) {
    balance = balance.add(amount);
  }

}
