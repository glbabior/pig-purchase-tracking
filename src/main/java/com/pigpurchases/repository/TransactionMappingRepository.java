package com.pigpurchases.repository;

import com.pigpurchases.model.TransactionMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionMappingRepository extends JpaRepository<TransactionMapping, Long> {
    List<TransactionMapping> findByAnalysisRunId(Long analysisRunId);
    List<TransactionMapping> findByAnalysisRunIdAndStatus(Long analysisRunId, TransactionMapping.Status status);
    Optional<TransactionMapping> findByAnalysisRunIdAndTransactionId(Long analysisRunId, Long transactionId);
    List<TransactionMapping> findByTransactionId(Long transactionId);
    /** How much spend still points at a budget entry — guards deleting one out from under it. */
    long countByBudgetEntryId(Long budgetEntryId);
    void deleteByAnalysisRunId(Long analysisRunId);
    void deleteByTransactionId(Long transactionId);
}
