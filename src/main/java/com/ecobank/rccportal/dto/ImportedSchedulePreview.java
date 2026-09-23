package com.ecobank.rccportal.dto;

/** Une ligne de planning réellement importée — agent, date, code de shift (ou heure de prise
 *  de poste pour un import à l'ancien format), libellé humain du code. */
public record ImportedSchedulePreview(String agentName, String workDate, String startTime, String shiftLabel) {
}
