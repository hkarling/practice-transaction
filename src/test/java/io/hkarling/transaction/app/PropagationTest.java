package io.hkarling.transaction.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hkarling.transaction.AbstractIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.NestedTransactionNotSupportedException;

@Slf4j
@SpringBootTest
class PropagationTest extends AbstractIntegrationTest {

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
  @DisplayName("JPA 환경에서는 NESTED가 근본적으로 지원되지 않는다")
  void nestedIsNotSupportedInJpaEnvironment() {
    assertThatThrownBy(() -> outerService.nestedThenFail("C"))
        .isInstanceOf(NestedTransactionNotSupportedException.class);
  }

}
