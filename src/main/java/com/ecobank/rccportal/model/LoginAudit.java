package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "LoginAudit", schema = "dbo")
public class LoginAudit extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "LoginAuditId")
    private Integer loginAuditId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    /** 'login_success' | 'login_failed' | 'locked' | 'password_reset' */
    @Column(name = "EventType", nullable = false, length = 30)
    private String eventType;

    @Column(name = "OccurredAt", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "PasswordAgeDays")
    private Integer passwordAgeDays;

    @Column(name = "IpAddress", length = 45)
    private String ipAddress;
}
