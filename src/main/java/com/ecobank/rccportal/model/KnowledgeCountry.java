package com.ecobank.rccportal.model;
import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "KnowledgeCountries", schema = "dbo")
public class KnowledgeCountry extends Auditable {
    @Id
    @Column(name = "CountryCode", length = 2)
    private String countryCode;

    @Column(name = "Label", nullable = false, length = 100)
    private String label;

    @Column(name = "FlagEmoji", length = 10)
    private String flagEmoji;

    @Column(name = "Currency", length = 100)
    private String currency;

    @Column(name = "Zone", length = 100)
    private String zone;

    @Column(name = "Regulator", length = 100)
    private String regulator;

    @Column(name = "AgencyCount", length = 50)
    private String agencyCount;

    @Column(name = "Phone", length = 20)
    private String phone;

    @Column(name = "SortOrder", nullable = false)
    private Integer sortOrder;
}