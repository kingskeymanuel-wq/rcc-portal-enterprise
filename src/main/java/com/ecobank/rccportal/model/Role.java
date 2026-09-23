package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "ROLES", schema = "dbo")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID", nullable = false)
    private Long id;

    @Column(name = "NAME", nullable = false, length = 255)
    private String name;

    @Column(name = "DESCRIPTION", length = 255)
    private String description;
}