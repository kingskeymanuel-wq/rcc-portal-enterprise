package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.AttendanceRecord;
import com.ecobank.rccportal.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Integer> {

    Optional<AttendanceRecord> findByUserAndWorkDate(User user, LocalDate workDate);

    /** Vue globale d'un jour donné (dashboard présence). */
    List<AttendanceRecord> findByWorkDateOrderByUser_NameAsc(LocalDate workDate);

    /** Historique d'un agent, du plus récent au plus ancien. */
    List<AttendanceRecord> findByUserOrderByWorkDateDesc(User user);
}
