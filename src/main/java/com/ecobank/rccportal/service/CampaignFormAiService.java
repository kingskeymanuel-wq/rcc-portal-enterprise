package com.ecobank.rccportal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Génération d'un formulaire de campagne à partir d'une description libre (« campagne de relance carte Visa pour les
 * clients salariés… »). Avec l'IA configurée (quality.ai.anthropic-key) : formulaire sur mesure — sections, script,
 * questions notées, logique conditionnelle et issues d'appel —, toujours contrôlé par CampaignFormEngine. Sans IA ou
 * en cas de réponse inexploitable : le modèle le plus proche de la bibliothèque, renommé d'après la demande.
 */
@Slf4j
@Service
public class CampaignFormAiService {

    private final AnthropicClient anthropic;

    public CampaignFormAiService(AnthropicClient anthropic) {
        this.anthropic = anthropic;
    }

    static final String SYSTEM = """
            Tu conçois des formulaires d'appels sortants (Outbound) pour Ecobank (banque de détail, Afrique de l'Ouest, montants en FCFA).
            Réponds UNIQUEMENT par un objet JSON valide, sans texte autour, au format :
            {"version":2,"settings":{"showProgress":true,"qualifiedThreshold":<0-100>},
             "sections":[{"id":"s1","title":"...","description":"...","script":"phrase que l'agent dit au client",
               "visibleIf":{"logic":"all","rules":[{"q":"<id question précédente>","op":"eq|neq|in|nin|contains|gt|gte|lt|lte|empty|notempty","value":"..."}]},
               "questions":[{"id":"snake_case","type":"SHORT_TEXT|LONG_TEXT|SINGLE|MULTIPLE|DROPDOWN|YES_NO|SCALE|RATING|NPS|NUMBER|AMOUNT|DATE|TIME|PHONE|EMAIL|MATRIX|RANKING|CONSENT|STATEMENT",
                 "label":"...","help":"...","required":true,
                 "options":[{"label":"...","score":<points>,"outcome":"GREEN|RED|YELLOW|PENDING (facultatif)"}],
                 "rows":[{"label":"..."}], "scale":{"min":1,"max":5,"minLabel":"...","maxLabel":"..."},
                 "validation":{"min":..,"max":..,"minDate":"today"}, "prefill":"client.phone (facultatif)",
                 "visibleIf":{...}}]}],
             "outcomes":[{"when":{"logic":"all","rules":[...]},"status":"GREEN|RED|YELLOW|PENDING","label":"..."}]}
            Règles : 1re section = prise de contact avec la question de disponibilité (options « Rappeler plus tard » → PENDING,
            « Mauvais numéro » → RED) ; les sections suivantes n'apparaissent que si le client est disponible ; une condition ne
            référence qu'une question PLACÉE AVANT ; donner des points (score) aux réponses qui qualifient le lead ;
            outcome YELLOW = rendez-vous pris, GREEN = interaction réussie / vente, RED = refus ou injoignable, PENDING = à rappeler ;
            8 à 16 questions, en français, utiles pour l'agent ; scripts courts et polis, avec {{client.prenom}} et {{agent.prenom}}.
            """;

    public Map<String, Object> generate(String prompt) {
        String p = prompt == null ? "" : prompt.trim();
        if (p.isEmpty()) throw com.ecobank.rccportal.util.ApiException.badRequest("Décrivez la campagne (produit, cible, objectif).");
        if (p.length() > 2000) p = p.substring(0, 2000);
        Map<String, Object> out = new LinkedHashMap<>();
        if (anthropic.isAvailable()) {
            try {
                String raw = anthropic.chat(SYSTEM, "Campagne à concevoir : " + p, 6000, 90);
                ObjectNode form = CampaignFormEngine.normalize(parseJson(raw));
                out.put("form", form);
                out.put("source", "IA");
                return out;
            } catch (RuntimeException e) {
                log.warn("[CAMPAGNE] Génération IA inexploitable, repli sur la bibliothèque : {}", e.getMessage());
                out.put("notice", "L'IA n'a pas produit de formulaire exploitable : modèle le plus proche proposé.");
            }
        } else {
            out.put("notice", "IA non configurée : modèle le plus proche de votre description, à ajuster.");
        }
        out.put("form", CampaignFormEngine.normalize(CampaignFormTemplates.closest(p)));
        out.put("source", "MODELE");
        return out;
    }

    static JsonNode parseJson(String raw) {
        if (raw == null) throw new IllegalStateException("réponse vide");
        int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalStateException("pas de JSON");
        try {
            return CampaignFormEngine.JSON.readTree(raw.substring(a, b + 1));
        } catch (Exception e) {
            throw new IllegalStateException("JSON illisible : " + e.getMessage());
        }
    }
}
