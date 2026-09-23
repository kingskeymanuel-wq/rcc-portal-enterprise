package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "KpiEvents", schema = "dbo")
public class KpiEvent extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "KpiEventId")
    private Integer kpiEventId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "UserId")
    private User user;

    @Column(name = "EventType", nullable = false, length = 50)
    private String eventType;

    @Column(name = "EventKey", length = 150)
    private String eventKey;

    @Column(name = "OccurredAt", nullable = false)
    private LocalDateTime occurredAt;
}
