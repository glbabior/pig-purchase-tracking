package com.pigpurchases.repository;

import com.pigpurchases.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByMonth(String month);
    List<Transaction> findByBudgetEntry_Id(Long budgetEntryId);
}
