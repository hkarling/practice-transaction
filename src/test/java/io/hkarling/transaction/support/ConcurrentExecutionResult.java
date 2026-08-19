package io.hkarling.transaction.support;

import java.util.List;

public record ConcurrentExecutionResult(
    int successCount,
    List<Exception> failures
) {

}
