package io.hkarling.transaction.infra;

import io.hkarling.transaction.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

}
