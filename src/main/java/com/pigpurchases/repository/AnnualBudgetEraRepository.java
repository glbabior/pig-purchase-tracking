package com.pigpurchases.repository;

import com.pigpurchases.model.AnnualBudgetEra;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AnnualBudgetEraRepository extends JpaRepository<AnnualBudgetEra, Long> {
}
