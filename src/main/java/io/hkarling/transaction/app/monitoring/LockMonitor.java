package io.hkarling.transaction.app.monitoring;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class LockMonitor {

  // PostgreSQL 공식 위키(Lock Monitoring)의 검증된 쿼리 그대로 사용
  private static final String BLOCKING_QUERY = """
      SELECT 
          blocked_locks.pid          AS blocked_pid,
          blocked_activity.usename   AS blocked_user,
          blocking_locks.pid         AS blocking_pid,
          blocking_activity.usename  AS blocking_user,
          blocked_activity.query     AS blocked_statement,
          blocking_activity.query    AS current_statement_in_blocking_process
      FROM pg_catalog.pg_locks blocked_locks
      JOIN pg_catalog.pg_stat_activity blocked_activity 
          ON blocked_activity.pid = blocked_locks.pid
      JOIN pg_catalog.pg_locks blocking_locks 
          ON blocking_locks.locktype = blocked_locks.locktype
         AND blocking_locks.database      IS NOT DISTINCT FROM blocked_locks.database
         AND blocking_locks.relation      IS NOT DISTINCT FROM blocked_locks.relation
         AND blocking_locks.page          IS NOT DISTINCT FROM blocked_locks.page
         AND blocking_locks.tuple         IS NOT DISTINCT FROM blocked_locks.tuple
         AND blocking_locks.virtualxid    IS NOT DISTINCT FROM blocked_locks.virtualxid
         AND blocking_locks.transactionid IS NOT DISTINCT FROM blocked_locks.transactionid
         AND blocking_locks.classid       IS NOT DISTINCT FROM blocked_locks.classid
         AND blocking_locks.objid         IS NOT DISTINCT FROM blocked_locks.objid
         AND blocking_locks.objsubid      IS NOT DISTINCT FROM blocked_locks.objsubid
         AND blocking_locks.pid != blocked_locks.pid
      JOIN pg_catalog.pg_stat_activity blocking_activity 
          ON blocking_activity.pid = blocking_locks.pid
      WHERE NOT blocked_locks.granted;
      """;

  private final JdbcTemplate jdbcTemplate;

  public List<BlockedSessionInfo> findBlockedSessions() {
    return jdbcTemplate.query(BLOCKING_QUERY, (rs, rowNum) -> new BlockedSessionInfo(
        rs.getInt("blocked_pid"),
        rs.getString("blocked_user"),
        rs.getInt("blocking_pid"),
        rs.getString("blocking_user"),
        rs.getString("blocked_statement"),
        rs.getString("current_statement_in_blocking_process")
    ));
  }

}
