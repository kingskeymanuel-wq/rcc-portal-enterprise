package com.ecobank.rccportal.model;

import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;

/** Clé composite (Scope, Key) de dbo.KvEntries. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode
@Embeddable
public class KvEntryId implements Serializable {

    private String scope;

    private String key;
}
