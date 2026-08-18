package io.hkarling.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class IsolationLevelTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @Autowired
  private DataSource dataSource;

  @Test
  @DisplayName("READ_COMMITTED는 같은 트랜잭션 안에서도 남이 커밋한 값을 다시 읽으면 바뀐다")
  void readCommittedAllowsNonRepeatableRead() throws Exception {
    long accountId = insertTestAccount("bob", "10000");

    CountDownLatch firstReadDone = new CountDownLatch(1);
    CountDownLatch updateCommitted = new CountDownLatch(1);

    BigDecimal[] firstRead = new BigDecimal[1];
    BigDecimal[] secondRead = new BigDecimal[1];

    ExecutorService pool = Executors.newFixedThreadPool(2);

    pool.submit(() -> {
      try (Connection conn = dataSource.getConnection()) {
        conn.setAutoCommit(false);
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        firstRead[0] = readBalance(conn, accountId);
        firstReadDone.countDown();

        updateCommitted.await(5, TimeUnit.SECONDS);
        secondRead[0] = readBalance(conn, accountId);

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
        }
        updateCommitted.countDown();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    pool.shutdown();
    pool.awaitTermination(10, TimeUnit.SECONDS);

    assertThat(firstRead[0]).isEqualByComparingTo("10000");
    assertThat(secondRead[0]).isEqualByComparingTo("5000");
  }

  private long insertTestAccount(String ownerName, String balance) throws SQLException {
    String sql = "INSERT INTO account (owner_name, balance) VALUES (?, ?) RETURNING id";
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
