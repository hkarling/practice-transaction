package io.hkarling.transaction.infra;

import io.hkarling.transaction.domain.OutboxEvent;
import io.hkarling.transaction.domain.OutboxStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

  List<OutboxEvent> findByStatus(OutboxStatus status);

}
