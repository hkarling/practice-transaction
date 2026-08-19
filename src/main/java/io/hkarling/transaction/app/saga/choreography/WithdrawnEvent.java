package io.hkarling.transaction.app.saga.choreography;

import java.math.BigDecimal;

public record WithdrawnEvent(
    String sagaId,
    Long fromId,
    Long toId,
    BigDecimal amount
) {

}
