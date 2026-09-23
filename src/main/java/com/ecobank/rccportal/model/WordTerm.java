package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Vocabulaire/acronymes bancaires réels (ex. RIB, OTP, DAB, CASHXPRESS) utilisés
 * par les jeux de mots (JoliGo, Mémoire, Pendu). Définitions génériques et
 * universellement correctes — jamais de politique ou chiffre propre à Ecobank
 * non vérifié (délais, frais...), pour rester fiable.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "WordTerms", schema = "dbo")
public class WordTerm extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "TermId")
    private Integer termId;

    @Column(name = "Term", nullable = false, length = 100)
    private String term;

    @Column(name = "Definition", nullable = false, length = 500)
    private String definition;

    @Column(name = "Category", length = 100)
    private String category;

    @Column(name = "Active", nullable = false)
    @Builder.Default
    private Boolean active = true;
}
