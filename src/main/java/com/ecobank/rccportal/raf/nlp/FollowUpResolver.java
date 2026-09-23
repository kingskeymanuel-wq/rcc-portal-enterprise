package com.ecobank.rccportal.raf.nlp;

import com.ecobank.rccportal.dto.RafSuggestion;
import com.ecobank.rccportal.raf.RafModels.RafDialogueState;
import com.ecobank.rccportal.raf.RafModels.RafRequest;
import com.ecobank.rccportal.util.SearchText;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Relances en contexte — transforme une réponse courte en commande déterministe :
 * <ul>
 *   <li>« 2 », « le deuxième » après une question de clarification → choix proposé n°2 ;</li>
 *   <li>« suivant », « c'est fait », « précédent », « étape 4 », « stop » en mode guidé ;</li>
 *   <li>« détails », « plus d'infos » → détail de la dernière réponse ;</li>
 *   <li>« et pour le Sénégal ? », « et en N2 ? » → même question, nouveau critère
 *       (géré par l'orchestrateur, voir {@link #isElliptical}).</li>
 * </ul>
 */
@Component
public class FollowUpResolver {

    private static final Pattern ELLIPSIS = Pattern.compile(
            "^(et|and|e|y|pareil|meme chose|same)( (pour|en|au|aux|a|dans|le|la|les|for|in|para|no|na|em|con))?\\b.*");
    private static final List<String> ORDINALS = List.of("premier", "deuxieme", "troisieme", "quatrieme", "cinquieme");
    private static final Set<String> NEXT = Set.of("suivant", "suivante", "etape suivante", "ok suivant", "c est fait", "fait",
            "ok fait", "next", "next step", "done", "seguinte", "proximo", "siguiente", "continuer", "continue");
    private static final Set<String> PREV = Set.of("precedent", "precedente", "etape precedente", "retour", "back", "previous",
            "anterior", "volver");
    private static final Set<String> STOP = Set.of("stop", "quitter", "arrete", "fin", "exit", "quit", "sair", "salir");
    private static final List<String> DETAILS = List.of("details", "detail", "plus de details", "donne moi les details",
            "detaille", "developpe", "plus d infos", "plus d informations", "dis m en plus", "more details", "mais detalhes", "mas detalles");
    private static final Pattern STEP = Pattern.compile("^(?:etape|step|passo|paso)\\s*(\\d{1,2})$");

    /** Relance courte qui n'a de sens qu'avec la question précédente. */
    public static boolean isElliptical(String normalized) {
        if (normalized == null) return false;
        return ELLIPSIS.matcher(normalized).matches() && normalized.split(" ").length <= 7;
    }

    /** Commande « raf:... » déduite du contexte, ou null si la question est à traiter normalement. */
    public String resolveCommand(RafRequest request) {
        if (request.command() != null && !request.command().isBlank()) return request.command();
        String n = request.normalized();
        RafDialogueState state = request.state();
        if (state == null || n == null) return null;

        List<RafSuggestion> choices = state.pendingChoices();
        if (choices != null && !choices.isEmpty()) {
            Integer index = null;
            if (n.matches("\\d{1,2}")) index = Integer.parseInt(n) - 1;
            else if (ORDINALS.indexOf(n.replaceFirst("^(le|la) ", "")) >= 0) index = ORDINALS.indexOf(n.replaceFirst("^(le|la) ", ""));
            if (index != null && index >= 0 && index < choices.size()) {
                RafSuggestion s = choices.get(index);
                return s.command() != null ? s.command() : "raf:ask:" + s.query();
            }
        }

        if (state.guided() != null) {
            int proc = state.guided().procedureId();
            int step = state.guided().step();
            if (NEXT.contains(n)) return "raf:proc:" + proc + ":step:" + (step + 1);
            if (PREV.contains(n)) return "raf:proc:" + proc + ":step:" + Math.max(1, step - 1);
            if (n.equals("repete") || n.equals("repeat") || n.equals("encore")) return "raf:proc:" + proc + ":step:" + step;
            if (STOP.contains(n)) return "raf:stop";
            var m = STEP.matcher(n);
            if (m.matches()) return "raf:proc:" + proc + ":step:" + Integer.parseInt(m.group(1));
        }

        if (state.lastDetails() != null && DETAILS.stream().anyMatch(d -> n.equals(d) || n.startsWith(d + " "))) {
            return "raf:details";
        }
        return null;
    }

    /** Recompose une relance elliptique avec la question précédente (« et pour le Sénégal ? »). */
    public String expandEllipsis(RafRequest request) {
        RafDialogueState state = request.state();
        if (state == null || state.lastQuestion() == null || !isElliptical(request.normalized())) return null;
        return state.lastQuestion() + " " + SearchText.normalize(request.question())
                .replaceFirst("^(et|and|e|y|pareil|meme chose|same)( (pour|for|para|en|in|em|au|aux|a|dans))?\\s*", "");
    }
}
