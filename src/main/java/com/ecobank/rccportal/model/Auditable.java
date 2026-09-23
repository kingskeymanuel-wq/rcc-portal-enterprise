package com.ecobank.rccportal.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Base commune à toutes les entités : CreatedAt/UpdatedAt sont posés automatiquement
 * par Hibernate (cohérent avec les DEFAULT SYSUTCDATETIME() du schéma SQL, qui restent
 * le filet de sécurité pour tout INSERT fait hors JPA, ex. les scripts de seed).
 */
@Getter
@Setter
@MappedSuperclass
public abstract class Auditable {

    @CreationTimestamp
    @Column(name = "CreatedAt", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "UpdatedAt", nullable = false)
    private LocalDateTime updatedAt;
}
