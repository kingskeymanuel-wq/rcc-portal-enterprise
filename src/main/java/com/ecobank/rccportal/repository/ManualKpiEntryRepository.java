package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.ManualKpiEntry;
import com.ecobank.rccportal.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ManualKpiEntryRepository extends JpaRepository<ManualKpiEntry, Integer> {
    List<ManualKpiEntry> findBySubjectOrderByPeriodDateDesc(User subject);
    List<ManualKpiEntry> findAllByOrderByPeriodDateDesc();
    List<ManualKpiEntry> findByImportBatchId(String importBatchId);
    long deleteByImportBatchId(String importBatchId);

    /** Historique des imports — un lot par ligne, du plus récent au plus ancien. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT e.importBatchId AS batchId, MIN(e.createdAt) AS importedAt, COUNT(e) AS entryCount,
                   MIN(e.enteredBy.username) AS enteredByUsername
            FROM ManualKpiEntry e
            WHERE e.importBatchId IS NOT NULL
            GROUP BY e.importBatchId
            ORDER BY MIN(e.createdAt) DESC
            """)
    List<ImportBatchSummary> findImportBatches();

    interface ImportBatchSummary {
        String getBatchId();
        java.time.LocalDateTime getImportedAt();
        Long getEntryCount();
        String getEnteredByUsername();
    }
}
