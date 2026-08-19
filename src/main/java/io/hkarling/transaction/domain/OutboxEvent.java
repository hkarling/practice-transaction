package io.hkarling.transaction.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
public class OutboxEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long fromId;

  private Long toId;

  private BigDecimal amount;

  @Enumerated(EnumType.STRING)
  private OutboxStatus status;

  public OutboxEvent(Long fromId, Long toId, BigDecimal amount) {
    this.fromId = fromId;
    this.toId = toId;
    this.amount = amount;
    this.status = OutboxStatus.PENDING;
  }

  public void markSent() {
    this.status = OutboxStatus.SENT;
  }
}
