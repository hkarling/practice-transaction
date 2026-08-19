package io.hkarling.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.hkarling.transaction.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@Slf4j
@SpringBootTest
class IsolationLevelTest extends AbstractIntegrationTest {

  @Autowired
  private DataSource dataSource;

  @Test
  @DisplayName("READ_COMMITTED는 같은 트랜잭션 안에서도 남이 커밋한 값을 다시 읽으면 바뀐다")
  void readCommittedAllowsNonRepeatableRead() throws Exception {
    BigDecimal[] result = runNonRepeatableReadExperiment(Connection.TRANSACTION_READ_COMMITTED);

    assertThat(result[0]).isEqualByComparingTo("10000");
    assertThat(result[1]).isEqualByComparingTo("5000"); // 바뀐 값이 보임 (non-repeatable read)
  }

  @Test
  @DisplayName("REPEATABLE_READ는 트랜잭션 시작 시점 스냅샷을 유지해 다시 읽어도 값이 안 바뀐다")
  void repeatableReadPreventsNonRepeatableRead() throws Exception {
    BigDecimal[] result = runNonRepeatableReadExperiment(Connection.TRANSACTION_REPEATABLE_READ);

    assertThat(result[0]).isEqualByComparingTo("10000");
    assertThat(result[1]).isEqualByComparingTo("10000"); // 원래 값 그대로 (스냅샷 유지)
  }

  private BigDecimal[] runNonRepeatableReadExperiment(int isolationLevel) throws InterruptedException, SQLException {
    long accountId = insertTestAccount("bob", "10000");

    CountDownLatch firstReadDone = new CountDownLatch(1);
    CountDownLatch updateCommitted = new CountDownLatch(1);
    BigDecimal[] result = new BigDecimal[2];

    ExecutorService pool = Executors.newFixedThreadPool(2);

    pool.submit(() -> {
      try (Connection conn = dataSource.getConnection()) {
        conn.setAutoCommit(false);
        conn.setTransactionIsolation(isolationLevel);

        result[0] = readBalance(conn, accountId);
        log.info("[A] 첫 번째 읽기: {}", result[0]);
        firstReadDone.countDown();

        updateCommitted.await(5, TimeUnit.SECONDS);
        result[1] = readBalance(conn, accountId);
        log.info("[A] 두 번째 읽기: {}", result[1]);

        conn.commit();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    pool.submit(() -> {
      try {
        firstReadDone.await(5, TimeUnit.SECONDS);
        try (Connection conn = dataSource.getConnection()) {
          conn.setAutoCommit(false);
          updateBalance(conn, accountId, "5000");
          conn.commit();
          log.info("[B] 업데이트 커밋 완료");
        }
        updateCommitted.countDown();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    pool.shutdown();
    pool.awaitTermination(10, TimeUnit.SECONDS);

    return result;
  }

  private long insertTestAccount(String ownerName, String balance) throws SQLException {
    String sql = "INSERT INTO account (owner_name, balance, version) VALUES (?, ?, 0) RETURNING id";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, ownerName);
      ps.setBigDecimal(2, new BigDecimal(balance));
      ps.executeUpdate();
      try (ResultSet rs = ps.getGeneratedKeys()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private BigDecimal readBalance(Connection conn, long accountId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM account WHERE id = ?")) {
      ps.setLong(1, accountId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBigDecimal("balance");
      }
    }
  }

  private void updateBalance(Connection conn, long accountId, String balance) throws Exception {
    try (PreparedStatement ps = conn.prepareStatement("UPDATE account SET balance = ? WHERE id = ?")) {
      ps.setBigDecimal(1, new BigDecimal(balance));
      ps.setLong(2, accountId);
      ps.executeUpdate();
    }
  }
}
