package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Règle SLA (délai de traitement) applicable à un motif de demande client RCC —
 * relevés, réclamations GAB, Mobile Money, assurance, CashXpress, crédits, etc.
 *
 * Distinct de {@link SlaTarget} : SlaTarget gère les seuils d'alerte internes
 * (workflows RH/ADMIN comme les congés), alors que SlaRule décrit les délais
 * *communiqués au client* pour chaque type de réclamation/demande RCC. C'est la
 * table consultée par RAF (voir RalphSearchService) pour ne jamais inventer un
 * délai et par le futur Supervisor AI pour mesurer le respect des SLA.
 *
 * Depuis l'ajout des fiches pôle (voir {@link RccPole}), une règle peut aussi être
 * rattachée à un pôle (pole) pour figurer dans sa fiche "Activités & délais" —
 * category tient alors lieu de "Produit ou service" et motif d'"Activité", exactement
 * la structure des fiches pôle diffusées en interne (Inbound, Outbound, Résolution,
 * Opérations, Business, Agences). pole reste nullable : les règles motif-client déjà
 * en place (non rattachées à un pôle) continuent de fonctionner à l'identique pour RAF.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "SlaRules", schema = "dbo")
public class SlaRule extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "SlaRuleId")
    private Integer slaRuleId;

    /** Pôle propriétaire de cette ligne d'activité (fiche pôle) — null pour les règles
     *  motif-client historiques non rattachées à un pôle. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PoleId")
    private RccPole pole;

    /** Ex. "Envoi de relevé de compte client ECI". */
    @Column(name = "Motif", nullable = false, length = 200)
    private String motif;

    /** Ex. "Documents bancaires", "Assurance", "Cartes bancaires et GAB", "Mobile Money", "CashXpress", "Crédits". */
    @Column(name = "Category", nullable = false, length = 100)
    private String category;

    /** "N1" | "N2" — niveau de traitement RCC. */
    @Column(name = "Level", length = 10)
    private String level;

    /** Délai numérique brut, en heures, pour tri/calcul (ex. 24, 48, 120 pour 5j ouvrés). */
    @Column(name = "SlaHours", nullable = false)
    private Integer slaHours;

    /** Libellé du délai tel que communiqué au client, ex. "24 heures", "5 jours ouvrés" —
     *  ou, pour une activité de fiche pôle, un libellé conditionnel plus long
     *  (ex. "Instantanée si reçue dans l'agence de domiciliation et 72H si transmise
     *  dans une autre agence"), d'où une limite portée à 160 caractères. */
    @Column(name = "SlaLabel", nullable = false, length = 160)
    private String slaLabel;

    /** Service/équipe destinataire du dossier, ex. "Digital", "Résolution", "Comité crédit". */
    @Column(name = "DestinationService", length = 100)
    private String destinationService;

    /** "HAUTE" | "MOYENNE" | "BASSE" — priorité indicative pour le tri/l'affichage. */
    @Column(name = "Priority", length = 20)
    private String priority;

    /** Vrai si une reverse/traitement automatique existe avant l'ouverture d'un dossier manuel. */
    @Column(name = "AutoEscalation", nullable = false)
    private Boolean autoEscalation;

    /** Note libre — ex. précisions de calcul du délai, cas particuliers. */
    @Column(name = "Notes", length = 500)
    private String notes;

    @Column(name = "IsActive", nullable = false)
    private Boolean isActive;

    @Column(name = "SortOrder", nullable = false)
    private Integer sortOrder;
}
