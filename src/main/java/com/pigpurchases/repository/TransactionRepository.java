package com.pigpurchases.repository;

import com.pigpurchases.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByMonth(String month);
    List<Transaction> findByBudgetEntry_Id(Long budgetEntryId);
    List<Transaction> findByStatementSourceId(Long statementSourceId);
    List<Transaction> findByStatementImportId(Long statementImportId);
    void deleteByStatementImportId(Long statementImportId);

    /**
     * Each import's earliest and latest transaction date, in one query rather than
     * one per import — the Ingest screen asks for every import of a source at once.
     * Rows come back as [statementImportId, min, max]; an import with no
     * transactions simply has no row.
     */
    @Query("select t.statementImportId, min(t.transactionDate), max(t.transactionDate) "
            + "from Transaction t where t.statementImportId in :importIds "
            + "group by t.statementImportId")
    List<Object[]> transactionDateRangesByImportIds(@Param("importIds") Collection<Long> importIds);
}
