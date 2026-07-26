package com.pigpurchases.repository;

import com.pigpurchases.model.DismissedDuplicate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DismissedDuplicateRepository extends JpaRepository<DismissedDuplicate, Long> {
    boolean existsBySignature(String signature);
}
