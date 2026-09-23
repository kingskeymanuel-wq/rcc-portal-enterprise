package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "TabPermissions", schema = "dbo")
public class TabPermission extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "TabPermissionId")
    private Integer tabPermissionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TeamId")
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "RoleId")
    private Role role;

    @Column(name = "TabCode", nullable = false, length = 50)
    private String tabCode;

    @Column(name = "IsAllowed", nullable = false)
    private Boolean isAllowed;
}
