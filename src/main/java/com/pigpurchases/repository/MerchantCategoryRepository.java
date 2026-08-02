package com.pigpurchases.repository;

import com.pigpurchases.model.MerchantCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MerchantCategoryRepository extends JpaRepository<MerchantCategory, Long> {
    Optional<MerchantCategory> findByMerchantKey(String merchantKey);
    /** Remembered answers pointing at one entry — cleared when that entry is deleted. */
    List<MerchantCategory> findByBudgetEntryId(Long budgetEntryId);
}
