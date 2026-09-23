package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "UserProfiles", schema = "dbo")
public class UserProfile extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UserProfileId")
    private Integer userProfileId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "UserId", nullable = false, unique = true)
    private User user;

    @Column(name = "PhotoUrl", length = 500)
    private String photoUrl;

    @Column(name = "ChatBackgroundUrl", length = 500)
    private String chatBackgroundUrl;

    @Column(name = "Birthdate")
    private LocalDate birthdate;

    @Column(name = "Phone", length = 30)
    private String phone;

    @Column(name = "Bio", length = 1000)
    private String bio;
}
