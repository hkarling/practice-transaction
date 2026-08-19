package io.hkarling.transaction.app.propagation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
@Service
public class PropagationDemoService {

  private final JdbcTemplate jdbcTemplate;

  @Transactional(propagation = Propagation.REQUIRED)
  public void logRequired(String message) {
    jdbcTemplate.update("INSERT INTO propagation_log(message) VALUES (?)", "REQUIRED: " + message);
    log.info("[REQUIRED] 삽입 완료: {}", message);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void logRequiresNew(String message) {
    jdbcTemplate.update("INSERT INTO propagation_log(message) VALUES (?)", "REQUIRED_NEW: " + message);
    log.info("[REQUIRES_NEW] 삽입 완료: {}", message);
  }

  @Transactional(propagation = Propagation.NESTED)
  public void logNested(String message) {
    jdbcTemplate.update("INSERT INTO propagation_log(message) VALUES (?)", "NESTED: " + message);
    log.info("[NESTED] 삽입 완료: {}", message);
  }
}
