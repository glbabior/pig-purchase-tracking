package com.pigpurchases.repository;

import com.pigpurchases.model.StatementSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StatementSourceRepository extends JpaRepository<StatementSource, Long> {
    StatementSource findByAccountName(String accountName);
}
