package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "MailRecipientGroups", schema = "dbo")
public class MailRecipientGroup extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "GroupId")
    private Integer groupId;

    @Column(name = "Label", nullable = false, length = 150)
    private String label;

    @Column(name = "Email", nullable = false, unique = true, length = 254)
    private String email;
}
