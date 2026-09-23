package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Store clé/valeur générique — reçoit les données non modélisées ailleurs
 * (eco_codestudio, eco_site_images, eco_cisco*, ...). Voir 01_schema.sql, section 10.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "KvEntries", schema = "dbo")
public class KvEntry extends Auditable {

    @EmbeddedId
    private KvEntryId id;

    @Lob
    @Column(name = "Value", nullable = false)
    private String value;
}
