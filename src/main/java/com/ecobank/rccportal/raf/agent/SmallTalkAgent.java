package com.ecobank.rccportal.raf.agent;

import com.ecobank.rccportal.dto.RafSuggestion;
import com.ecobank.rccportal.raf.RafModels.*;
import com.ecobank.rccportal.raf.RafText;
import com.ecobank.rccportal.raf.nlp.SmallTalk;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dialogue : RAF converse comme un collègue (salutations, « comment vas-tu », humeur de l'agent,
 * remerciements, identité, blagues, heure/date, frustration…) puis ramène naturellement vers ce
 * qu'il sait faire. Les formulations varient d'une fois à l'autre ; la catégorie vient de
 * {@link SmallTalk} (motifs sur le texte normalisé), sans aucune IA externe.
 */
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

    private static List<RafSuggestion> few() {
        return starters().subList(0, 4);
    }

    private static final List<String> JOKES_FR = List.of(
            "Pourquoi les banquiers n'aiment pas les ascenseurs ? Parce qu'ils préfèrent les taux qui montent… doucement. 😄",
            "Un client demande : « Mon compte est-il bien réveillé ? » — Le conseiller : « Oui, il a même pris son café : il est créditeur ! » ☕",
            "Quelle est la carte préférée d'un conseiller RCC ? La carte… de la patience ! 💳",
            "Le GAB a dit au client : « Ne t'inquiète pas, je ne garde que les mauvais souvenirs… et parfois les cartes. » 🏧",
            "Pourquoi le RIB est toujours calme ? Parce qu'il connaît toutes ses coordonnées. 😉");
    private static final List<String> JOKES_EN = List.of(
            "Why did the ATM break up with the card? It felt the relationship was just a transaction. 😄",
            "What's a call-center agent's favourite exercise? Holding… the line! ☎️",
            "Why are bankers great at parties? They know how to raise interest. 💸");

    /** Variante stable pour une même phrase dans la même minute, différente d'une fois à l'autre. */
    private static String pick(List<String> options, RafRequest request) {
        int seed = (request.normalized() + request.now().getHour() + request.now().getMinute()).hashCode();
        return options.get(Math.floorMod(seed, options.size()));
    }

    @Override
    public AgentAnswer answer(RafRequest request, double routerScore) {
        if (routerScore < 0.5) return AgentAnswer.notFound(id(), RafIntent.SMALL_TALK, "pas une formule de dialogue");
        String lang = request.lang();
        boolean en = "en".equals(lang);
        SmallTalk.Kind kind = SmallTalk.detect(request.normalized());
        if (kind == null) {
            // Routé ici par le mot-clé « aide » / « que sais-tu faire » : présentation des capacités.
            kind = SmallTalk.Kind.CAPABILITIES;
        }
        String text;
        List<RafSuggestion> chips = List.of();
        switch (kind) {
            case HOW_ARE_YOU -> {
                text = en ? pick(List.of("I'm doing great, thanks for asking! 😊 And you, how's your day going?",
                                "All good on my side — the portal data is fresh and I'm ready. How about you?"), request)
                        : pick(List.of("Je vais très bien, merci de demander ! 😊 Et toi, ta journée se passe bien ?",
                                "Ça va super, merci ! Les procédures sont à jour et je suis prêt. Et de ton côté, comment ça va ?",
                                "Toujours en forme — pas de pause café pour moi ! ☕ Et toi, ça va ?"), request);
                chips = List.of(new RafSuggestion("😊 Ça va bien", "ça va bien et toi", null),
                        new RafSuggestion("😮‍💨 Journée chargée", "journée chargée", null),
                        new RafSuggestion("💡 Que sais-tu faire ?", "que sais tu faire", null));
            }
            case MOOD_GOOD -> {
                text = en ? "Great to hear! 🙌 What can I help you with — a procedure, an SLA, a customer email?"
                        : pick(List.of("Content de l'entendre ! 🙌 Sur quoi je peux t'aider : une procédure, un délai SLA, un mail client ?",
                                "Top ! Alors on attaque ? Dis-moi le cas client ou la procédure qui t'intéresse.",
                                "Parfait, on garde cette énergie 💪 ! Qu'est-ce que je peux faire pour toi ?"), request);
                chips = few();
            }
            case MOOD_BAD -> {
                text = en ? "Hang in there 💙 — tough days happen. Take a short breather if you can, then tell me the case: I'll find the procedure and the right words for the customer."
                        : pick(List.of("Courage 💙, ce genre de journée arrive à tout le monde. Si tu peux, prends 2 minutes pour souffler, "
                                        + "puis dis-moi le cas qui te bloque : je te trouve la procédure et les bons mots pour le client.",
                                "Je comprends, certaines journées sont lourdes. Tu n'es pas seul(e) : dis-moi ce qui coince et on le règle ensemble, étape par étape.",
                                "Respire un bon coup 😮‍💨. Un client difficile ? Je peux te donner la fiche appel (quoi dire, quoi faire, délai) pour gagner du temps."), request);
                chips = List.of(new RafSuggestion("📞 Client mécontent", "que dire à un client mécontent", null),
                        new RafSuggestion("🗓 Ma pause / planning", "mon planning aujourd'hui", null),
                        new RafSuggestion("📋 Procédure guidée", "procédure opposition carte", null));
            }
            case THANKS -> text = en ? pick(List.of("You're welcome! 😊 Anything else?", "Anytime! Happy to help."), request)
                    : pick(List.of("Avec plaisir ! 😊 Autre chose ?", "De rien, c'est fait pour ça ! Je reste là si besoin.",
                            "Je t'en prie ! N'hésite pas si un autre cas client se présente."), request);
            case BYE -> text = en ? "See you soon! Have a great shift. 👋"
                    : pick(List.of("À bientôt ! Bonne fin de shift 👋", "Au revoir et bon courage pour la suite ! 👋",
                            "À plus tard ! Je reste disponible dès que tu as besoin. 😊"), request);
            case WISHES -> text = en ? "Thank you, same to you! 😊" : "Merci, à toi aussi ! 😊 Je suis là si tu as besoin.";
            case WHO_ARE_YOU -> {
                text = en ? "I'm RAF, the RCC assistant built into this portal. I'm not an external AI: I reason only over the portal's own data "
                        + "(procedures, SLAs, glossary, branches, templates, your schedule) and I tell you where each answer comes from."
                        : "Je suis RAF, l'assistant du RCC intégré au portail. Je ne suis pas une IA externe : je raisonne uniquement sur les données "
                        + "du portail (procédures, SLA, glossaire, agences, modèles de mail, ton planning) et je t'indique toujours d'où vient ma réponse. "
                        + "Mon but : te faire gagner du temps pendant tes appels. 🚀";
                chips = few();
            }
            case CAPABILITIES -> {
                text = RafText.get(lang, "help") + "\n\n• 📞 Fiche appel : quoi faire, quoi dire, délai, modèle\n• 📋 Procédures en mode guidé"
                        + "\n• ⏱ SLA officiels + date d'échéance\n• 📖 Glossaire bancaire\n• 🏦 Agences par pays/ville"
                        + "\n• 🗓 Ton planning et ton retard éventuel\n• ✉ Mails clients pré-remplis\n• ✔ Réponses vérifiées par la QA"
                        + (en ? "" : "\n\nEt on peut aussi discuter un peu 😊");
                chips = starters();
            }
            case COMPLIMENT -> text = en ? "Thanks, that's kind! 😊 I learn from the portal every day." : pick(List.of(
                    "Merci, ça fait plaisir ! 😊 Je m'améliore avec chaque procédure ajoutée par la QA.",
                    "Oh merci ! C'est toi qui fais le travail avec les clients, moi je t'aide juste à aller plus vite. 💪"), request);
            case FRUSTRATION -> {
                text = en ? "Sorry about that 🙏 — let's fix it. Rephrase with the product or procedure name (e.g. \"card blocked\", \"SLA ATM claim\") or pick a topic below."
                        : "Désolé, je n'ai pas été à la hauteur 🙏. Réessayons : donne-moi le produit ou la procédure (ex. « carte bloquée », "
                        + "« délai réclamation GAB », « RIB ») ou choisis une piste ci-dessous. Tu peux aussi cliquer sur 👎 pour que la QA complète ma base.";
                chips = few();
            }
            case JOKE -> text = pick(en ? JOKES_EN : JOKES_FR, request);
            case TIME -> text = (en ? "It's " : "Il est ") + request.now().format(DateTimeFormatter.ofPattern("HH:mm")) + (en ? "." : ".") ;
            case DATE -> text = (en ? "Today is " : "Nous sommes le ")
                    + request.now().format(DateTimeFormatter.ofPattern(en ? "EEEE, MMMM d, yyyy" : "EEEE d MMMM yyyy", en ? Locale.ENGLISH : Locale.FRENCH)) + ".";
            case PRESENCE -> {
                text = en ? "Yes, I'm here! 👋 What do you need?" : "Oui, je suis là ! 👋 Qu'est-ce qu'il te faut ?";
                chips = few();
            }
            case ACK -> text = en ? "👍 Anything else?" : pick(List.of("👍 Très bien. Autre chose ?", "Parfait ! Je reste dispo.", "Ça marche 😊"), request);
            default -> {
                boolean evening = request.now().getHour() >= 18;
                text = en ? RafText.get(lang, "hello")
                        : (evening ? "Bonsoir ! " : "Bonjour ! ") + pick(List.of(
                                "Je suis RAF 😊 Comment ça va aujourd'hui ? Dis-moi sur quoi je peux t'aider : procédure, SLA, fiche appel, agences, mail client…",
                                "Ravi de te voir ! Je suis prêt à t'aider sur tes appels : procédures guidées, délais SLA, glossaire, modèles de mail…",
                                "RAF à ton service 🚀 Un cas client en cours ? Décris-le moi, je trouve la marche à suivre."), request);
                chips = starters();
            }
        }
        if (!"fr".equals(lang) && !en) {
            // pt / es : formules de base traduites (RafText), le reste en anglais.
            if (kind == SmallTalk.Kind.GREETING) text = RafText.get(lang, "hello");
            if (kind == SmallTalk.Kind.THANKS) text = RafText.get(lang, "thanks");
            if (kind == SmallTalk.Kind.BYE) text = RafText.get(lang, "bye");
            if (kind == SmallTalk.Kind.HOW_ARE_YOU) text = RafText.get(lang, "howareyou");
        }
        return new AgentAnswer(id(), RafIntent.SMALL_TALK, 1.0, true, text, null, List.of(), chips, null, null, List.of("dialogue"));
    }
}
