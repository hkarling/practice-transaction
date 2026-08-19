package io.hkarling.transaction.infra;

import io.hkarling.transaction.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

  boolean existsByRequestKey(String requestKey);
  
}
