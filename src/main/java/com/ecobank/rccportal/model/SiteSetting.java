package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/** Réglages d'apparence pilotables depuis QA/Admin (photo de connexion, opacité...) — clé/valeur simple. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "SiteSettings", schema = "dbo")
public class SiteSetting {

    @Id
    @Column(name = "SettingKey", length = 100)
    private String settingKey;

    @Column(name = "SettingValue", length = 500)
    private String settingValue;
}
