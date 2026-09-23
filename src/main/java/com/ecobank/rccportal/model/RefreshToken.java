package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "RefreshTokens", schema = "dbo")
public class RefreshToken extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "RefreshTokenId")
    private Integer refreshTokenId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "Jti", nullable = false, unique = true)
    private UUID jti;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    @Column(name = "ExpiresAt", nullable = false)
    private LocalDateTime expiresAt;

    /** Jeton d'accès de la gateway SAGED (AuthGatewayClient), conservé uniquement pour pouvoir
     *  se déconnecter proprement côté gateway au logout (voir AuthService.logout()) — best-effort,
     *  null si le login n'est pas passé par la gateway (compte de test / bypass). */
    @Column(name = "GatewayAccessToken", length = 4000)
    private String gatewayAccessToken;
}
