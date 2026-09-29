package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record LoginAuditResponse(
        Integer id,
        String username,
        String fullName,
        String eventType,
        LocalDateTime occurredAt,
        Integer passwordAgeDays,
        String ipAddress,
        /** Filiale sur 2 lettres (CI, TG…) — Côte d'Ivoire si non renseignée. */
        String countryCode,
        /** Équipe (INBOUND_VOICE, INBOUND_MAIL, TCHAT, RAFIKI, CIB, OUTBOUND) ou profil (ADMIN, RH, QA…). */
        String team
) {
}
