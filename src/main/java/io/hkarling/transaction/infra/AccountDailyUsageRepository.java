package io.hkarling.transaction.infra;

import io.hkarling.transaction.domain.AccountDailyUsage;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountDailyUsageRepository extends JpaRepository<AccountDailyUsage, Long> {

  Optional<AccountDailyUsage> findByAccountIdAndUsageDate(Long accountId, LocalDate usageDate);

}
