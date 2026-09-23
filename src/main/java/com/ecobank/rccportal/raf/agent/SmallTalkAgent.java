package com.ecobank.rccportal.raf.agent;

import com.ecobank.rccportal.dto.RafSuggestion;
import com.ecobank.rccportal.raf.RafModels.*;
import com.ecobank.rccportal.raf.RafText;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** Civilités et « que sais-tu faire ? » — en fr/en/pt/es, avec des exemples cliquables. */
@Component
public class SmallTalkAgent implements RafAgent {

    public String id() { return "smalltalk"; }

    public String label() { return "Dialogue"; }

    public Set<RafIntent> intents() { return Set.of(RafIntent.SMALL_TALK, RafIntent.HELP); }

    public static List<RafSuggestion> starters() {
        return List.of(
                new RafSuggestion("📞 Fiche appel", "que dire au client pour une carte bloquée", null),
                new RafSuggestion("⏱ Délai SLA", "délai réclamation retrait GAB", null),
                new RafSuggestion("📋 Procédure guidée", "procédure opposition carte", null),
                new RafSuggestion("🏦 Agences", "agences en Côte d'Ivoire", null),
                new RafSuggestion("🗓 Mon planning", "mon planning aujourd'hui", null),
                new RafSuggestion("📖 Glossaire", "c'est quoi un RIB", null),
                new RafSuggestion("✉ Rédiger un mail", "rédige le mail de réclamation", null));
    }

    @Override
    public AgentAnswer answer(RafRequest request, double routerScore) {
        String n = request.normalized();
        String lang = request.lang();
        String text;
        List<RafSuggestion> chips = List.of();
        if (routerScore < 0.5) return AgentAnswer.notFound(id(), RafIntent.SMALL_TALK, "pas une formule de politesse");
        if (n.startsWith("merci") || n.startsWith("thank") || n.startsWith("obrigad") || n.startsWith("gracias")) {
            text = RafText.get(lang, "thanks");
        } else if (n.contains("revoir") || n.equals("bye") || n.contains("bientot") || n.contains("bonne journee")) {
            text = RafText.get(lang, "bye");
        } else if (n.contains("ca va") || n.contains("vas bien")) {
            text = RafText.get(lang, "howareyou");
        } else if (n.contains("sais") || n.contains("help") || n.contains("aide") || n.contains("can you") || n.contains("sabes")) {
            text = RafText.get(lang, "help") + "\n\n• 📞 Fiche appel : quoi faire, quoi dire, délai, modèle\n• 📋 Procédures en mode guidé"
                    + "\n• ⏱ SLA officiels + date d'échéance\n• 📖 Glossaire bancaire\n• 🏦 Agences par pays/ville"
                    + "\n• 🗓 Ton planning et ton retard éventuel\n• ✉ Mails clients pré-remplis\n• ✔ Réponses vérifiées par la QA";
            chips = starters();
        } else {
            text = RafText.get(lang, "hello");
            chips = starters();
        }
        return new AgentAnswer(id(), RafIntent.SMALL_TALK, 1.0, true, text, null, List.of(), chips, null, null, List.of("dialogue"));
    }
}
