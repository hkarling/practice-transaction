package io.hkarling.transaction.app.monitoring;

public record BlockedSessionInfo(
    Integer blockedPid,
    String blockedUser,
    Integer blockingPid,
    String blockingUser,
    String blockedStatement,
    String currentStatementInBlockingProcess
) {

}
