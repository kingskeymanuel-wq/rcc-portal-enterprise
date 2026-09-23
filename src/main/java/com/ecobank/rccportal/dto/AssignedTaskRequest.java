package com.ecobank.rccportal.dto;

import java.time.LocalDate;

/**
 * Création d'une note/tâche. assignedToUsername et assignedToTeamCode null tous les deux =
 * tâche personnelle (pour soi-même) — ouvert à tout utilisateur. L'un des deux renseigné =
 * assignation à quelqu'un d'autre, réservé QA/Admin côté service.
 */
public record AssignedTaskRequest(
        String title, String description, String assignedToUsername, String assignedToTeamCode, LocalDate dueDate, String priority) {
}
