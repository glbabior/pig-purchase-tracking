package com.pigpurchases.repository;

import com.pigpurchases.model.AnalysisRunSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnalysisRunSourceRepository extends JpaRepository<AnalysisRunSource, Long> {
    List<AnalysisRunSource> findByAnalysisRunId(Long analysisRunId);
    List<AnalysisRunSource> findByStatementImportId(Long statementImportId);
    void deleteByAnalysisRunId(Long analysisRunId);
}
