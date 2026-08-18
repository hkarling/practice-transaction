package io.hkarling.transaction.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.catchThrowable;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Slf4j
@Testcontainers
@SpringBootTest
class PropagationTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer("postgres:16");

  @Autowired
  private PropagationOuterService outerService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS propagation_log (id BIGSERIAL PRIMARY KEY, message VARCHAR(255))");
    jdbcTemplate.update("DELETE FROM propagation_log");
  }

  @Test
  @DisplayName("REQUIRED는 바깥 트랜잭션에 합류해서, 바깥이 롤백되면 같이 롤백된다")
  void requiredJoinsOuterTransactionAndRollsBackTogether() {
    assertThatThrownBy(() -> outerService.requiredThenFail("A"))
        .isInstanceOf(RuntimeException.class);

    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM propagation_log", Integer.class);
    log.info("[REQUIRED] 바깥 롤백 후 남은 로그 수: {}", count);
    assertThat(count).isZero();
  }

  @Test
  @DisplayName("REQUIRES_NEW는 독립된 트랜잭션이라 바깥이 롤백돼도 살아남는다")
  void requiresNewSurvivesOuterRollback() {
    assertThatThrownBy(() -> outerService.requiresNewThenFail("B"))
        .isInstanceOf(RuntimeException.class);

    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM propagation_log", Integer.class);
    log.info("[REQUIRES_NEW] 바깥 롤백 후 남은 로그 수: {}", count);
    assertThat(count).isEqualTo(1);
  }

  @Test
  @DisplayName("NESTED 전파 속성을 시도해본다 — 기본 설정으로 되는지 확인")
  void nestedThenFailBehavior() {
    Throwable thrown = catchThrowable(() -> outerService.nestedThenFail("C"));

    log.info("[NESTED] 던져진 예외 타입: {}", thrown.getClass().getName());
    log.info("[NESTED] 메시지: {}", thrown.getMessage());

    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM propagation_log", Integer.class);
    log.info("[NESTED] 바깥 롤백 후 남은 로그 수: {}", count);
  }

}