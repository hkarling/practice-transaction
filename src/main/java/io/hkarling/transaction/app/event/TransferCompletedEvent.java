package io.hkarling.transaction.app.event;

import java.math.BigDecimal;

public record TransferCompletedEvent(
    Long fromId,
    Long toId,
    BigDecimal amount
) {

}
