package com.ecobank.rccportal.dto;

/** Décision de l'agent visé (peer) ou du Team Leader sur une demande de permutation. comment
 *  optionnel dans les deux cas (utile surtout en cas de refus, jamais obligatoire ici contrairement
 *  au refus de planning mensuel — une permutation entre pairs reste d'abord une affaire personnelle). */
public record ShiftSwapDecisionRequest(boolean approve, String comment) {
}
