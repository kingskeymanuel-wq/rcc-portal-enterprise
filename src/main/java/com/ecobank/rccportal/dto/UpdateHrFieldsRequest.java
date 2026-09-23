package com.ecobank.rccportal.dto;

import java.time.LocalDate;

/** Édition restreinte — contrat + résidence uniquement, ouverte à RH/Superviseur/Team
 *  Leader (sur leur équipe)/Admin, contrairement à UpdateUserRequest (admin uniquement,
 *  tous les champs y compris username/rôle/équipe dirigée). */
public record UpdateHrFieldsRequest(
        String contractType,
        String contractStatus,
        LocalDate contractStartDate,
        LocalDate contractEndDate,
        String residencePlace
) {
}
