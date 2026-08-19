package io.hkarling.transaction.infra;

import io.hkarling.transaction.domain.AccountDailyUsage;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountDailyUsageRepository extends JpaRepository<AccountDailyUsage, Long> {

  Optional<AccountDailyUsage> findByAccountIdAndUsageDate(Long accountId, LocalDate usageDate);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from AccountDailyUsage u where u.accountId = :accountId and u.usageDate = :usageDate")
  Optional<AccountDailyUsage> findByAccountIdAndUsageDateForUpdate(@Param("accountId") Long accountId,
      @Param("usageDate") LocalDate usageDate);
}
