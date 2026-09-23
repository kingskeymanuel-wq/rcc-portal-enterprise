package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "SERVICES", schema = "dbo")
public class RccService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID", nullable = false)
    private Long id;

    @Column(name = "NAME", nullable = false, length = 100)
    private String name;

    @Column(name = "DESCRIPTION", length = 500)
    private String description;

    @Column(name = "E_PATH", length = 150)
    private String path;

    @Column(name = "CODE", nullable = false, length = 50)
    private String code;

    @Column(name = "ICON", length = 100)
    private String icon;

    @Column(name = "COLOR", length = 20)
    private String color;

    @Column(name = "STATUS", length = 30)
    private String status;

    @Column(name = "ENABLED")
    private Boolean enabled;

    @Column(name = "DISPLAY_ORDER")
    private Integer displayOrder;

    @Column(name = "OPEN_IN_NEW_TAB")
    private Boolean openInNewTab;

    @Column(name = "IS_PORTAL_APP")
    private Boolean portalApp;

    @Column(name = "PROXY_CODE", length = 50)
    private String proxyCode;
}