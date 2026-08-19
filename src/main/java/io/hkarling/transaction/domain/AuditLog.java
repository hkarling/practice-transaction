package io.hkarling.transaction.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String action;

  private Long fromId;

  private Long toId;

  private BigDecimal amount;

  private boolean success;

  private String message;

  public AuditLog(String action, Long fromId, Long toId, BigDecimal amount, boolean success, String message) {
    this.action = action;
    this.fromId = fromId;
    this.toId = toId;
    this.amount = amount;
    this.success = success;
    this.message = message;
  }
}
