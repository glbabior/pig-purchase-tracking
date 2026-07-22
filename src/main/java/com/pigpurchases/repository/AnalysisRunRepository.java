package com.pigpurchases.repository;

import com.pigpurchases.model.AnalysisRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, Long> {
    List<AnalysisRun> findAllByOrderByMonthDesc();
    Optional<AnalysisRun> findByMonth(String month);
}
