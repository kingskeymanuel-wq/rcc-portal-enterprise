package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
        name = "FavoriteProcedures",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {
                        "UserId",
                        "ProcedureId"
                })
        }
)
public class FavoriteProcedure extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "FavoriteId")
    private Integer favoriteId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ProcedureId", nullable = false)
    private Procedure procedure;
}
