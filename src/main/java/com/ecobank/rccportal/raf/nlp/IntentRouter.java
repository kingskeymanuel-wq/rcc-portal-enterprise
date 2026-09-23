package com.ecobank.rccportal.raf.nlp;

import com.ecobank.rccportal.raf.RafCatalog;
import com.ecobank.rccportal.raf.RafDocs;
import com.ecobank.rccportal.raf.RafModels.IntentScore;
import com.ecobank.rccportal.raf.RafModels.RafIntent;
import com.ecobank.rccportal.raf.RafModels.RafRequest;
import com.ecobank.rccportal.util.SearchText;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Routeur d'intentions — déterministe et explicable (chaque point de score est justifié dans
 * {@code evidence}, restitué par « Pourquoi cette réponse ? »), sans modèle d'IA :
 * <ol>
 *   <li>déclencheurs lexicaux fr/en/pt/es (« délai », « c'est quoi », « agence »...) ;</li>
 *   <li>entités repérées (pays/ville → agences, date + « mon » → planning) ;</li>
 *   <li>correspondances réelles dans les données (un motif SLA, un titre de procédure ou un
 *       terme du glossaire qui correspond à la question renforce l'intention associée) ;</li>
 *   <li>contexte : une relance elliptique (« et pour le Sénégal ? ») reprend l'intention précédente.</li>
 * </ol>
 */
@Component
public class IntentRouter {

    private record Trigger(String phrase, double weight) {
    }

    private static final Map<RafIntent, List<Trigger>> LEXICON = new EnumMap<>(RafIntent.class);

    static {
        LEXICON.put(RafIntent.SLA, triggers("sla 1.0", "delai 0.8", "delais 0.8", "combien de temps 0.7", "echeance 0.8",
                "quand sera traite 0.8", "temps de traitement 0.9", "turnaround 0.8", "how long 0.7", "prazo 0.8", "plazo 0.8"));
        LEXICON.put(RafIntent.GLOSSARY, triggers("c est quoi 0.6", "definition 0.9", "que veut dire 0.9", "signifie 0.8",
                "veut dire 0.7", "what is 0.6", "what does 0.6", "o que e 0.6", "que es 0.6", "sigle 0.8", "acronyme 0.8"));
        LEXICON.put(RafIntent.BRANCH, triggers("agence 0.9", "agences 0.9", "branch 0.9", "branches 0.9", "agencia 0.9",
                "adresse 0.5", "horaire d ouverture 0.7", "horaires 0.4", "plus proche 0.6", "guichet 0.5", "dab 0.3"));
        LEXICON.put(RafIntent.MY_SHIFT, triggers("mon planning 1.0", "mon shift 0.9", "mes horaires 0.9", "je commence 0.8",
                "je travaille 0.8", "suis je en retard 1.0", "je suis en retard 0.9", "mon retard 0.9", "mon statut 0.7",
                "ma pause 0.6", "my schedule 1.0", "my shift 0.9", "am i late 1.0", "meu horario 1.0", "mi horario 1.0"));
        LEXICON.put(RafIntent.TEMPLATE, triggers("redige 0.8", "rediger 0.8", "modele 0.8", "mail 0.5", "courrier 0.6",
                "message au client 0.8", "reponse au client 0.7", "draft 0.8", "email 0.5", "write 0.5", "template 0.8"));
        LEXICON.put(RafIntent.CALL_CARD, triggers("que dire au client 1.0", "quoi dire 0.9", "quoi repondre 0.9",
                "que repondre 0.9", "fiche appel 1.0", "client appelle 0.9", "le client se plaint 0.8", "client veut 0.6",
                "what to say 0.9", "customer calls 0.9"));
        LEXICON.put(RafIntent.PROCEDURE, triggers("procedure 0.9", "comment traiter 0.8", "comment faire 0.7", "etapes 0.7",
                "que faire 0.6", "comment 0.3", "process 0.6", "procedimento 0.9", "procedimiento 0.9", "how to 0.6"));
        LEXICON.put(RafIntent.HELP, triggers("que sais tu faire 1.0", "tu sais faire quoi 1.0", "aide moi 0.4", "help 0.6",
                "what can you do 1.0", "o que sabes fazer 1.0", "que sabes hacer 1.0"));
    }

    private static final Set<String> GREETINGS = Set.of("bonjour", "salut", "bonsoir", "coucou", "hello", "hi", "bjr", "slt",
            "ola", "hola", "buenos dias", "bom dia", "merci", "merci beaucoup", "merci bien", "thanks", "thank you", "obrigado",
            "gracias", "ca va", "comment ca va", "tu vas bien", "au revoir", "bye", "a bientot", "bonne journee");

    private static List<Trigger> triggers(String... specs) {
        List<Trigger> out = new ArrayList<>();
        for (String spec : specs) {
            int i = spec.lastIndexOf(' ');
            out.add(new Trigger(spec.substring(0, i), Double.parseDouble(spec.substring(i + 1))));
        }
        return out;
    }

    private final RafCatalog catalog;

    public IntentRouter(RafCatalog catalog) {
        this.catalog = catalog;
    }

    public List<IntentScore> route(RafRequest request) {
        String text = " " + request.normalized() + " ";
        Map<RafIntent, Double> raw = new EnumMap<>(RafIntent.class);
        Map<RafIntent, List<String>> evidence = new EnumMap<>(RafIntent.class);

        if (GREETINGS.contains(request.normalized())) {
            add(raw, evidence, RafIntent.SMALL_TALK, 3.0, "formule de politesse");
        }
        LEXICON.forEach((intent, list) -> {
            for (Trigger t : list) {
                if (text.contains(" " + t.phrase() + " ")) add(raw, evidence, intent, t.weight(), "mot-clé « " + t.phrase() + " »");
            }
        });

        var e = request.entities();
        if (e.city() != null || e.countryCode() != null) {
            add(raw, evidence, RafIntent.BRANCH, e.city() != null ? 0.3 : 0.15, "lieu repéré");
        }
        boolean firstPerson = text.contains(" mon ") || text.contains(" ma ") || text.contains(" mes ") || text.contains(" je ")
                || text.contains(" j ") || text.contains(" my ") || text.contains(" i ");
        if (e.date() != null && firstPerson) add(raw, evidence, RafIntent.MY_SHIFT, 0.4, "date + première personne");
        if (e.level() != null) add(raw, evidence, RafIntent.SLA, 0.1, "niveau " + e.level());

        // Correspondances dans les données du portail.
        RafDocs.Snapshot data = catalog.snapshot();
        List<String> terms = request.terms();
        if (!terms.isEmpty()) {
            double sla = bestNormalized(request, data.slaRules().stream()
                    .map(r -> new SearchText.Field[]{SearchText.Field.of(r.motif(), 3), SearchText.Field.of(r.category(), 2)}).toList());
            if (sla > 0) add(raw, evidence, RafIntent.SLA, 0.5 * sla, "motif SLA correspondant");
            double proc = bestNormalized(request, data.procedures().stream()
                    .map(p -> new SearchText.Field[]{SearchText.Field.of(p.title(), 3)}).toList());
            if (proc > 0) add(raw, evidence, RafIntent.PROCEDURE, 0.5 * proc, "titre de procédure correspondant");
            double qa = bestNormalized(request, data.verifiedQa().stream()
                    .map(q -> new SearchText.Field[]{SearchText.Field.of(q.question(), 3), SearchText.Field.of(q.tags(), 2)}).toList());
            if (qa > 0) add(raw, evidence, RafIntent.VERIFIED_QA, 0.6 * qa, "question vérifiée proche");
            double tpl = bestNormalized(request, data.mailTemplates().stream()
                    .map(m -> new SearchText.Field[]{SearchText.Field.of(m.subject(), 3), SearchText.Field.of(m.categoryLabel(), 2)}).toList());
            if (tpl > 0 && raw.containsKey(RafIntent.TEMPLATE)) add(raw, evidence, RafIntent.TEMPLATE, 0.3 * tpl, "modèle de mail correspondant");
            String stripped = stripGlossaryTriggers(request.normalized());
            for (RafDocs.TermDoc t : data.terms()) {
                if (!stripped.isBlank() && SearchText.normalize(t.term()).equals(stripped)) {
                    add(raw, evidence, RafIntent.GLOSSARY, 0.8, "terme du glossaire « " + t.term() + " »");
                    break;
                }
            }
            if (sla > 0 && proc > 0 && text.contains(" client ")) add(raw, evidence, RafIntent.CALL_CARD, 0.3, "procédure + SLA + client");
        }

        if (request.state() != null && request.state().lastIntent() != null && FollowUpResolver.isElliptical(request.normalized())) {
            add(raw, evidence, request.state().lastIntent(), 0.5, "suite de la question précédente");
        }

        List<IntentScore> out = new ArrayList<>();
        for (RafIntent intent : RafIntent.values()) {
            double r = raw.getOrDefault(intent, 0.0);
            double score = 1 - Math.exp(-r);
            if (intent == RafIntent.KNOWLEDGE) {
                score = Math.max(score, 0.2); // la recherche documentaire tourne toujours en filet de sécurité
                evidence.computeIfAbsent(intent, k -> new ArrayList<>()).add("recherche documentaire de secours");
            }
            if (score > 0) out.add(new IntentScore(intent, score, List.copyOf(evidence.getOrDefault(intent, List.of()))));
        }
        out.sort(Comparator.comparingDouble(IntentScore::score).reversed());
        return out;
    }

    static String stripGlossaryTriggers(String normalized) {
        String s = " " + normalized + " ";
        for (Trigger t : LEXICON.get(RafIntent.GLOSSARY)) s = s.replace(" " + t.phrase() + " ", " ");
        for (String w : List.of("un", "une", "le", "la", "les", "l", "d", "de", "du", "a", "an", "the", "um", "uma", "o", "el")) {
            s = s.replace(" " + w + " ", " ");
        }
        return s.trim().replaceAll("\\s+", " ");
    }

    /** Meilleur score normalisé (0..1) de la requête sur une collection de documents. */
    private static double bestNormalized(RafRequest request, List<SearchText.Field[]> docs) {
        double best = 0;
        int n = request.terms().size();
        for (SearchText.Field[] fields : docs) {
            SearchText.Match m = SearchText.score(request.question(), request.terms(), fields);
            if (!m.isRelevant(n)) continue;
            double maxWeight = 0;
            for (SearchText.Field f : fields) maxWeight = Math.max(maxWeight, f.weight());
            best = Math.max(best, Math.min(1, m.score() / (n * maxWeight)));
        }
        return best;
    }

    private static void add(Map<RafIntent, Double> raw, Map<RafIntent, List<String>> evidence, RafIntent intent, double w, String why) {
        raw.merge(intent, w, Double::sum);
        evidence.computeIfAbsent(intent, k -> new ArrayList<>()).add(why);
    }
}
