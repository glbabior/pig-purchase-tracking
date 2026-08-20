package com.pigpurchases.repository;

import com.pigpurchases.model.BudgetAmountEra;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BudgetAmountEraRepository extends JpaRepository<BudgetAmountEra, Long> {
    List<BudgetAmountEra> findByBudgetEntryId(Long budgetEntryId);
    void deleteByBudgetEntryId(Long budgetEntryId);
}
