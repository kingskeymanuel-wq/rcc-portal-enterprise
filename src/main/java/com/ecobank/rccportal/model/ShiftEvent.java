package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Journal d'événements de shift — append-only. L'agent ne peut qu'ajouter un
 * événement (voir ShiftController) ; jamais le modifier ni le supprimer. Sert
 * de trace de présentéisme non modifiable, consultable par QA/admin.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "ShiftEvents", schema = "dbo")
public class ShiftEvent extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ShiftEventId")
    private Integer shiftEventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    /** LOGIN | PAUSE_START | PAUSE_END | LUNCH_START | LUNCH_END | SHIFT_END */
    @Column(name = "EventType", nullable = false, length = 20)
    private String eventType;

    @Column(name = "OccurredAt", nullable = false)
    private LocalDateTime occurredAt;
}