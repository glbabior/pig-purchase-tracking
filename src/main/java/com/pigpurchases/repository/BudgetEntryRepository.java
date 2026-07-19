package com.pigpurchases.repository;

import com.pigpurchases.model.BudgetEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BudgetEntryRepository extends JpaRepository<BudgetEntry, Long> {
    BudgetEntry findByName(String name);
}
