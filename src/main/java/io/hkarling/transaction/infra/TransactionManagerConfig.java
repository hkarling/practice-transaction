package io.hkarling.transaction.infra;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class TransactionManagerConfig {

  @Bean
  public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
    JpaTransactionManager transactionManager = new JpaTransactionManager(entityManagerFactory);
    transactionManager.setJpaDialect(new HibernateJpaDialect());
    transactionManager.setNestedTransactionAllowed(true);
    return transactionManager;
  }
}
