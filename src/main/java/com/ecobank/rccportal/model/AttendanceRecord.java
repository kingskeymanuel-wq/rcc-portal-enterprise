package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "AttendanceRecords", schema = "dbo",
       uniqueConstraints = @UniqueConstraint(columnNames = {"UserId", "WorkDate"}))
public class AttendanceRecord extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AttendanceId")
    private Integer attendanceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    @Column(name = "WorkDate", nullable = false)
    private LocalDate workDate;

    /** 'present' | 'absent' */
    @Column(name = "Status", nullable = false, length = 20)
    private String status;

    @Column(name = "ArrivalTime")
    private LocalTime arrivalTime;

    @Column(name = "DepartureTime")
    private LocalTime departureTime;
}
