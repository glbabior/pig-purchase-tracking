package com.pigpurchases.repository;

import com.pigpurchases.model.MonthStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MonthStatusRepository extends JpaRepository<MonthStatus, String> {
}
