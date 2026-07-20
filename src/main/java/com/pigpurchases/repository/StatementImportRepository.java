package com.pigpurchases.repository;

import com.pigpurchases.model.StatementImport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StatementImportRepository extends JpaRepository<StatementImport, Long> {
    List<StatementImport> findByStatementSourceIdOrderByStatementDateDesc(Long statementSourceId);
    Optional<StatementImport> findByStatementSourceIdAndStatementDate(Long statementSourceId, LocalDate statementDate);
}
