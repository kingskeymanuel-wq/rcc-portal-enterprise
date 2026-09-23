package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Rubrique de la Formation — même principe que KnowledgeCategory (Base de connaissances) :
 * une vignette avec image, remplace le regroupement par simple texte libre (Course.category)
 * utilisé jusqu'ici. Course.category reste le lien logique (par titre), pas de clé étrangère
 * stricte pour ne jamais casser les cours déjà créés avec une thématique en texte libre.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "CourseCategories", schema = "dbo")
public class CourseCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CourseCategoryId")
    private Integer courseCategoryId;

    /** Doit correspondre exactement à Course.category pour que la vignette affiche les bons cours. */
    @Column(name = "Title", nullable = false, length = 100, unique = true)
    private String title;

    @Column(name = "ImageUrl", length = 500)
    private String imageUrl;

    @Column(name = "SortOrder", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;
}
