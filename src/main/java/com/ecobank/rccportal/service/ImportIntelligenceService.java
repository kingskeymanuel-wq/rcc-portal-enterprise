package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Assistance IA (Copilot Studio) pour les imports Excel de Performances (KPI) et de Planning
 * par équipe — utilisée par ManualKpiEntryService et ScheduleService.
 *
 * Règle absolue, comme partout ailleurs dans le projet : Copilot Studio n'invente JAMAIS de
 * donnée. Deux usages seulement, tous deux sans risque sur l'intégrité des chiffres :
 *
 *  1. resolveAmbiguousName — quand un nom importé ne correspond exactement à aucun agent
 *     connu, on demande à Copilot Studio de choisir le plus probable PARMI les agents déjà
 *     réels (variante orthographique, nom de jeune fille, ordre prénom/nom...), jamais d'en
 *     proposer un nouveau. Si la réponse ne correspond pas mot pour mot à un candidat de la
 *     liste fournie, elle est ignorée — on retombe sur la création d'un nouveau compte, comme
 *     avant. Aucune valeur numérique n'est jamais soumise à cette méthode.
 *
 *  2. summarizeAnomalies — les anomalies (valeurs négatives, taux > 100%, nouveaux comptes
 *     créés...) sont déjà détectées par du code Java déterministe AVANT d'appeler cette
 *     méthode ; Copilot Studio se contente de rédiger une synthèse lisible en français à
 *     l'intention de la QA/Admin qui a lancé l'import, jamais de recalculer ou de modifier
 *     les valeurs elles-mêmes.
 *
 * Non bloquant dans les deux cas : si Copilot Studio n'est pas configuré ou échoue, l'import
 * continue normalement avec le comportement historique (création de compte / pas de synthèse).
 */
@Service
public class ImportIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(ImportIntelligenceService.class);

    private final CopilotAgentService copilotAgentService;

    public ImportIntelligenceService(CopilotAgentService copilotAgentService) {
        this.copilotAgentService = copilotAgentService;
    }

    public boolean isAvailable() {
        return copilotAgentService.isConfigured();
    }

    /**
     * @param rawName          nom tel qu'il apparaît dans le fichier importé
     * @param candidateNames   noms complets des agents déjà existants (même filiale/service si possible)
     * @return le nom candidat choisi par Copilot Studio, UNIQUEMENT s'il correspond mot pour mot
     *         (insensible à la casse) à l'un des candidats fournis — sinon vide (pas de match).
     */
    public Optional<String> resolveAmbiguousName(String rawName, List<String> candidateNames) {
        if (!isAvailable() || rawName == null || rawName.isBlank() || candidateNames == null || candidateNames.isEmpty()) {
            return Optional.empty();
        }
        // Évite un appel inutile si la liste de candidats est déraisonnablement longue —
        // dans ce cas la comparaison manuelle serait de toute façon peu fiable.
        if (candidateNames.size() > 300) {
            return Optional.empty();
        }

        String candidateList = String.join("\n", candidateNames.stream().map(n -> "- " + n).toList());
        String systemPrompt = "Tu compares un nom importé d'un fichier Excel RH à une liste fermée d'agents déjà " +
                "existants dans la base d'un centre d'appel Ecobank. Le nom importé peut contenir une faute de " +
                "frappe, un ordre prénom/nom inversé, un nom de jeune fille, ou des accents différents. " +
                "Réponds STRICTEMENT par le nom exact d'un agent de la liste ci-dessous s'il s'agit très " +
                "probablement de la même personne, recopié caractère pour caractère depuis la liste. " +
                "Si aucun agent de la liste ne correspond clairement, réponds uniquement par le mot NOUVEAU. " +
                "Ne réponds rien d'autre, aucune explication, aucune ponctuation supplémentaire.\n\n" +
                "Agents existants :\n" + candidateList;

        try {
            String answer = copilotAgentService.ask(systemPrompt + "\n\nNom importé à identifier : " + rawName.trim());
            String cleaned = answer == null ? "" : answer.trim();
            for (String candidate : candidateNames) {
                if (candidate.equalsIgnoreCase(cleaned)) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty(); // réponse "NOUVEAU", vide, ou hors liste — jamais utilisée
        } catch (ApiException e) {
            log.warn("Import : résolution de nom ambigu via Copilot Studio échouée pour « {} » : {}", rawName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Rédige une synthèse française courte des anomalies déjà détectées par le code d'import
     * (jamais de recalcul, uniquement de la mise en forme lisible). Renvoie null si rien à
     * signaler, si Copilot Studio n'est pas configuré, ou en cas d'échec — l'import n'est
     * jamais bloqué par cette étape.
     */
    public String summarizeAnomalies(List<String> valueAnomalies, List<String> nameResolutionNotes) {
        boolean hasAnomalies = valueAnomalies != null && !valueAnomalies.isEmpty();
        boolean hasNotes = nameResolutionNotes != null && !nameResolutionNotes.isEmpty();
        if (!isAvailable() || (!hasAnomalies && !hasNotes)) {
            return null;
        }

        StringBuilder data = new StringBuilder();
        if (hasAnomalies) {
            data.append("Valeurs inhabituelles détectées (déjà enregistrées telles quelles, à vérifier) :\n");
            valueAnomalies.forEach(a -> data.append("— ").append(a).append("\n"));
        }
        if (hasNotes) {
            data.append("\nRapprochements de noms effectués pendant l'import :\n");
            nameResolutionNotes.forEach(n -> data.append("— ").append(n).append("\n"));
        }

        String systemPrompt = "Tu rédiges une note courte en français (4-6 phrases maximum) à l'intention d'un " +
                "responsable QA du RCC Portal Ecobank qui vient de lancer un import de fichier Excel. On te " +
                "fournit une liste déjà établie d'anomalies et de rapprochements de noms — ne recalcule et " +
                "n'invente AUCUN chiffre, contente-toi de résumer clairement ce qui mérite une vérification " +
                "humaine, par ordre de priorité. Reste factuel et actionnable.";

        try {
            return copilotAgentService.ask(systemPrompt + "\n\n" + data);
        } catch (ApiException e) {
            log.warn("Import : synthèse Copilot Studio échouée : {}", e.getMessage());
            return null;
        }
    }
}
