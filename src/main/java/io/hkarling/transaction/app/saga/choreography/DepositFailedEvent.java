package io.hkarling.transaction.app.saga.choreography;

import java.math.BigDecimal;

public record DepositFailedEvent(
    String sagaId,
    Long fromId,
    Long toId,
    BigDecimal amount,
    String reason
) {

}
