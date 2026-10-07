package com.ecobank.rccportal.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.text.Normalizer;
import java.util.*;

/**
 * Modèles de formulaires de campagne prêts à l'emploi (banque de détail, Outbound) : scripts d'appel, questions de
 * qualification notées, logique conditionnelle et issue d'appel suggérée. Servent aussi de base à la génération
 * automatique quand l'IA n'est pas configurée.
 */
public final class CampaignFormTemplates {

    private CampaignFormTemplates() {}

    public record Template(String code, String title, String description, String icon, ObjectNode form) {}

    public static List<Template> all() {
        return List.of(televente(), digital(), loan(), card(), satisfaction(), savings(), kyc(), insurance());
    }

    public static Optional<Template> byCode(String code) {
        return all().stream().filter(t -> t.code().equalsIgnoreCase(code)).findFirst();
    }

    // ───────────── Petit langage de construction ─────────────

    static final class F {
        final ObjectNode form = CampaignFormEngine.JSON.createObjectNode();
        ArrayNode sections;
        ArrayNode questions;
        int s, q;

        F(int threshold) {
            form.put("version", 2);
            form.putObject("settings").put("showProgress", true).put("qualifiedThreshold", threshold);
            sections = form.putArray("sections");
            form.putArray("outcomes");
        }

        F section(String title, String description, String script) {
            ObjectNode sec = sections.addObject();
            sec.put("id", "s" + (++s)).put("title", title);
            if (description != null) sec.put("description", description);
            if (script != null) sec.put("script", script);
            questions = sec.putArray("questions");
            return this;
        }

        F sectionIf(String qid, String op, Object value) {
            cond((ObjectNode) sections.get(sections.size() - 1), qid, op, value);
            return this;
        }

        ObjectNode question(String id, String type, String label, boolean required) {
            ObjectNode n = questions.addObject();
            n.put("id", id).put("type", type).put("label", label).put("required", required);
            q++;
            return n;
        }

        /** Choix « libellé:points:issue » — ex. "Très intéressé:30", "Souhaite un RDV:40:YELLOW". */
        F choice(String id, String type, String label, boolean required, String... options) {
            ObjectNode n = question(id, type, label, required);
            ArrayNode opts = n.putArray("options");
            int i = 0;
            for (String o : options) {
                String[] p = o.split(":");
                ObjectNode on = opts.addObject().put("id", id + "o" + (++i)).put("label", p[0]);
                if (p.length > 1 && !p[1].isBlank()) on.put("score", Integer.parseInt(p[1]));
                if (p.length > 2) on.put("outcome", p[2]);
            }
            return this;
        }

        F text(String id, String type, String label, boolean required, String help) {
            ObjectNode n = question(id, type, label, required);
            if (help != null) n.put("help", help);
            return this;
        }

        F amount(String id, String label, boolean required, long min, long max) {
            ObjectNode n = question(id, "AMOUNT", label, required);
            n.putObject("validation").put("min", min).put("max", max);
            n.put("help", "Montant en FCFA");
            return this;
        }

        F scale(String id, String type, String label, boolean required, int max, double weight, String minLabel, String maxLabel) {
            ObjectNode n = question(id, type, label, required);
            ObjectNode sc = n.putObject("scale").put("min", "NPS".equals(type) ? 0 : 1).put("max", max);
            if (minLabel != null) sc.put("minLabel", minLabel);
            if (maxLabel != null) sc.put("maxLabel", maxLabel);
            if (weight > 0) n.put("weight", weight);
            return this;
        }

        F statement(String id, String label, String help) {
            ObjectNode n = question(id, "STATEMENT", label, false);
            if (help != null) n.put("help", help);
            return this;
        }

        /** Pas à pas lié à la dernière question (ou section si aucune question encore) — ex. "ecobank-mobile/enrolement". */
        F guide(String ref) {
            if (questions.isEmpty()) ((ObjectNode) sections.get(sections.size() - 1)).put("guide", ref);
            else ((ObjectNode) questions.get(questions.size() - 1)).put("guide", ref);
            return this;
        }

        /** Argumentaire : bloc d'information avec ses points forts (aucune réponse attendue). */
        F pitch(String id, String label, String icon, String... bullets) {
            ObjectNode n = question(id, "STATEMENT", label, false);
            if (icon != null) n.put("icon", icon);
            ArrayNode b = n.putArray("bullets");
            for (String x : bullets) b.add(x);
            return this;
        }

        private ObjectNode option(String label) {
            for (var o : questions.get(questions.size() - 1).path("options")) if (label.equals(o.path("label").asText())) return (ObjectNode) o;
            throw new IllegalArgumentException("Option inconnue : " + label);
        }

        /** Réponse conseillée affichée quand ce choix est sélectionné (anticipation d'objection, argument). */
        F reply(String optionLabel, String text) { option(optionLabel).put("reply", text); return this; }

        /** Pas à pas proposé quand ce choix est sélectionné. */
        F optGuide(String optionLabel, String ref) { option(optionLabel).put("guide", ref); return this; }

        F item_consent(String id, String label) {
            question(id, "CONSENT", label, true);
            return this;
        }

        F prefill(String source) {
            ((ObjectNode) questions.get(questions.size() - 1)).put("prefill", source);
            return this;
        }

        F validation(String key, Object value) {
            ObjectNode n = (ObjectNode) questions.get(questions.size() - 1);
            ObjectNode v = n.has("validation") ? (ObjectNode) n.get("validation") : n.putObject("validation");
            if (value instanceof Integer i) v.put(key, i); else v.put(key, String.valueOf(value));
            return this;
        }

        F showIf(String qid, String op, Object value) {
            cond((ObjectNode) questions.get(questions.size() - 1), qid, op, value);
            return this;
        }

        F outcome(String status, String label, String qid, String op, Object value) {
            ObjectNode o = ((ArrayNode) form.get("outcomes")).addObject();
            o.put("status", status).put("label", label);
            cond(o, qid, op, value, "when");
            return this;
        }

        private static void cond(ObjectNode target, String qid, String op, Object value) { cond(target, qid, op, value, "visibleIf"); }

        private static void cond(ObjectNode target, String qid, String op, Object value, String key) {
            ObjectNode c = target.putObject(key);
            c.put("logic", "all");
            ObjectNode r = c.putArray("rules").addObject();
            r.put("q", qid).put("op", op);
            if (value instanceof String[] arr) { ArrayNode a = r.putArray("value"); for (String x : arr) a.add(x); }
            else if (value != null) r.put("value", String.valueOf(value));
        }
    }

    private static final String HELLO = "Bonjour {{client.prenom}}, je suis {{agent.prenom}} d'Ecobank. Je vous appelle au sujet de {{campagne}} — avez-vous deux minutes ?";

    // ───────────── Modèles ─────────────

    static Template loan() {
        F f = new F(60)
                .section("Prise de contact", null, HELLO)
                .choice("joignable", "SINGLE", "Le client est-il disponible ?", true, "Oui, disponible:0", "Rappeler plus tard:0:PENDING", "Mauvais numéro:0:RED")
                .section("Besoin de financement", "Comprendre le projet du client.", "Avez-vous un projet en cours pour lequel un financement vous aiderait ?")
                .sectionIf("joignable", "eq", "Oui, disponible")
                .choice("interet", "SINGLE", "Intérêt pour un prêt personnel", true, "Très intéressé:30", "Intéressé:20", "Peu intéressé:5", "Pas intéressé:0")
                .choice("projet", "MULTIPLE", "Objet du financement", false, "Équipement / mobilier:5", "Véhicule:10", "Travaux / habitat:10", "Scolarité:5", "Santé:5", "Événement familial:5")
                .showIf("interet", "in", new String[]{"Très intéressé", "Intéressé", "Peu intéressé"})
                .amount("montant", "Montant souhaité", false, 50_000, 100_000_000).showIf("interet", "in", new String[]{"Très intéressé", "Intéressé"})
                .choice("duree", "DROPDOWN", "Durée de remboursement souhaitée", false, "6 mois", "12 mois", "24 mois", "36 mois", "48 mois", "60 mois")
                .showIf("interet", "in", new String[]{"Très intéressé", "Intéressé"})
                .choice("revenu", "SINGLE", "Revenu mensuel domicilié chez Ecobank", false, "Salaire domicilié:25", "Revenus réguliers non domiciliés:10", "Pas de revenu régulier:0")
                .showIf("interet", "in", new String[]{"Très intéressé", "Intéressé"})
                .text("motif_refus", "LONG_TEXT", "Pourquoi le client n'est-il pas intéressé ?", false, "Objection exacte du client : elle sert à adapter l'offre.")
                .showIf("interet", "eq", "Pas intéressé")
                .section("Suite à donner", null, "Je vous propose un rendez-vous avec un conseiller pour monter votre dossier.")
                .sectionIf("interet", "in", new String[]{"Très intéressé", "Intéressé"})
                .choice("suite", "SINGLE", "Suite convenue", true, "Rendez-vous en agence:40:YELLOW", "Envoi de la simulation par e-mail:20", "Rappel à une date précise:10:PENDING")
                .text("email", "EMAIL", "E-mail du client", false, null).showIf("suite", "eq", "Envoi de la simulation par e-mail")
                .text("rappel", "DATE", "Date de rappel", false, null).validation("minDate", "today").showIf("suite", "eq", "Rappel à une date précise")
                .outcome("RED", "Pas intéressé", "interet", "eq", "Pas intéressé");
        return new Template("PRET", "Prêt personnel", "Qualification d'un besoin de crédit : projet, montant, revenus, rendez-vous conseiller.", "bi-cash-coin", f.form);
    }

    static Template card() {
        F f = new F(55)
                .section("Prise de contact", null, HELLO)
                .choice("joignable", "SINGLE", "Le client est-il disponible ?", true, "Oui, disponible:0", "Rappeler plus tard:0:PENDING", "Mauvais numéro:0:RED")
                .section("Usage actuel", null, "Utilisez-vous aujourd'hui une carte bancaire pour vos paiements et retraits ?")
                .sectionIf("joignable", "eq", "Oui, disponible")
                .choice("carte_actuelle", "SINGLE", "Le client a-t-il déjà une carte ?", true, "Non, aucune carte:20", "Oui, carte Ecobank:5", "Oui, carte d'une autre banque:15")
                .choice("usages", "MULTIPLE", "Usages envisagés", false, "Paiements en ligne:10", "Voyages / devises:10", "Retraits DAB:5", "Paiements en magasin:5")
                .choice("offre", "SINGLE", "Carte proposée", true, "Visa Classic:10", "Visa Gold:20", "Mastercard prépayée:10", "Visa Infinite:25")
                .choice("decision", "SINGLE", "Décision du client", true, "Souscrit maintenant:40:GREEN", "Souhaite un RDV en agence:30:YELLOW", "Réfléchit, à rappeler:10:PENDING", "Refuse:0")
                .text("objection", "LONG_TEXT", "Objection du client", false, null).showIf("decision", "eq", "Refuse")
                .choice("agence", "DROPDOWN", "Agence de retrait de la carte", false, "Agence principale", "Plateau", "Cocody", "Marcory", "Yopougon", "Treichville", "Autre")
                .showIf("decision", "in", new String[]{"Souscrit maintenant", "Souhaite un RDV en agence"})
                .item_consent("consent", "Le client accepte d'être recontacté pour le suivi de sa demande");
        return new Template("CARTE", "Carte bancaire", "Équipement carte : besoin, offre adaptée, décision et agence de retrait.", "bi-credit-card-2-front-fill", f.form);
    }

    static Template satisfaction() {
        F f = new F(0)
                .section("Introduction", null, "Bonjour {{client.prenom}}, Ecobank aimerait connaître votre avis sur nos services — cela prend 2 minutes.")
                .scale("nps", "NPS", "Sur une échelle de 0 à 10, recommanderiez-vous Ecobank à un proche ?", true, 10, 0, "Pas du tout", "Certainement")
                .scale("csat", "RATING", "Note globale de votre dernière expérience", true, 5, 0, null, null)
                .choice("canal", "MULTIPLE", "Canaux utilisés ces 3 derniers mois", false, "Agence", "Ecobank Mobile", "Internet Banking", "Centre de relation client", "Guichet automatique");
        f.question("criteres", "MATRIX", "Votre satisfaction par critère", false).put("help", "Une réponse par ligne");
        ObjectNode m = (ObjectNode) f.questions.get(f.questions.size() - 1);
        ArrayNode cols = m.putArray("options");
        for (String c : List.of("Insatisfait", "Moyen", "Satisfait", "Très satisfait")) cols.addObject().put("label", c);
        ArrayNode rows = m.putArray("rows");
        for (String r : List.of("Accueil", "Délai de traitement", "Clarté des informations", "Résolution du problème")) rows.addObject().put("label", r);
        f.text("detracteur", "LONG_TEXT", "Qu'est-ce qui vous a déçu ?", true, "Reformuler et noter les faits précis.")
                .showIf("nps", "lte", "6")
                .choice("rappel_reclamation", "YES_NO", "Souhaitez-vous qu'un conseiller vous rappelle pour régler ce point ?", false)
                .showIf("nps", "lte", "6")
                .text("promoteur", "LONG_TEXT", "Qu'appréciez-vous le plus chez Ecobank ?", false, null)
                .showIf("nps", "gte", "9")
                .outcome("YELLOW", "Détracteur à rappeler", "rappel_reclamation", "eq", "Oui")
                .outcome("GREEN", "Enquête complétée", "nps", "notempty", null);
        return new Template("NPS", "Satisfaction client (NPS)", "Recommandation 0–10, note de l'expérience, satisfaction par critère, verbatims et rappel des détracteurs.", "bi-emoji-smile-fill", f.form);
    }

    static final String GM = "ecobank-mobile/";

    /** Outbound Digitalisation : équiper et rendre autonome sur Ecobank Mobile, avec les pas à pas de chaque fonctionnalité. */
    static Template digital() {
        F f = new F(50)
                .section("Prise de contact", null, HELLO)
                .choice("joignable", "SINGLE", "Le client est-il disponible ?", true, "Oui, disponible:0", "Rappeler plus tard:0:PENDING", "Mauvais numéro:0:RED")
                .section("Situation digitale du client", "Comprendre où en est le client avant de proposer.", "Aujourd'hui, comment faites-vous vos opérations : en agence, au guichet automatique, sur votre téléphone ?")
                .sectionIf("joignable", "eq", "Oui, disponible")
                .choice("smartphone", "YES_NO", "Le client a-t-il un smartphone (Android ou iPhone) avec Internet ?", true)
                .choice("app", "SINGLE", "Ecobank Mobile", true, "Installée et utilisée:5", "Installée mais jamais activée:20", "Pas installée:20", "Ne connaît pas l'application:20")
                .showIf("smartphone", "eq", "Oui")
                .choice("usages", "MULTIPLE", "Opérations que le client fait aujourd'hui en se déplaçant", false,
                        "Consulter son solde:5", "Faire des virements:10", "Payer des factures (eau, électricité):10", "Acheter du crédit:5",
                        "Envoyer de l'argent sur Mobile Money:10", "Retirer de l'argent:5", "Payer la scolarité:10")
                .showIf("smartphone", "eq", "Oui")
                .section("Ce que l'application change pour le client", "Présenter les bénéfices en partant des usages cités.",
                        "D'après ce que vous me dites, l'application vous éviterait ces déplacements : tout se fait depuis votre téléphone, 24h/24.")
                .sectionIf("smartphone", "eq", "Oui")
                .pitch("benefices", "Bénéfices à mettre en avant", "bi-stars",
                        "Gratuite, sur Play Store et App Store, activable en quelques minutes pendant l'appel.",
                        "Solde et opérations 24h/24 : plus de file d'attente pour un simple contrôle.",
                        "Virements vers Ecobank, les autres banques du pays et les comptes Ecobank en Afrique.",
                        "Factures d'eau, d'électricité et scolarité payées depuis la maison.",
                        "Crédit MTN / Orange et transferts vers MTN MoMo en quelques secondes.",
                        "Xpress Cash : retirer ou faire retirer de l'argent sans carte.",
                        "Sécurité : code PIN à 6 chiffres, empreinte digitale, code SMS sur les opérations sensibles.")
                .section("Activation accompagnée", "Activer l'application avec le client, écran par écran.",
                        "Je vous accompagne maintenant : cela prend 3 à 5 minutes. Ouvrez Play Store ou App Store…")
                .sectionIf("app", "in", new String[]{"Installée mais jamais activée", "Pas installée", "Ne connaît pas l'application"})
                .guide(GM + "enrolement")
                .choice("profil", "SINGLE", "Situation du client pour l'activation", true,
                        "Client Ecobank avec carte de débit:10", "Client Ecobank avec profil banque par Internet:10", "Nouveau client — ouverture d'un compte Xpress:20")
                .reply("Client Ecobank avec carte de débit", "Le client choisit « Oui, j'ai un compte Ecobank » puis « Utiliser ma carte de débit ». Il saisit lui-même le numéro, la date et le CVV — ne jamais les demander.")
                .reply("Client Ecobank avec profil banque par Internet", "Le client choisit « Oui, j'ai un compte Ecobank » puis « Utiliser mon profil de banque par Internet » et saisit ses identifiants Ecobank Online.")
                .reply("Nouveau client — ouverture d'un compte Xpress", "Le client choisit « Non, je suis nouveau à Ecobank » puis « Ouvrez un compte Ecobank » : compte Xpress ouvert immédiatement avec sa pièce d'identité.")
                .optGuide("Client Ecobank avec carte de débit", GM + "enrolement").optGuide("Client Ecobank avec profil banque par Internet", GM + "enrolement")
                .optGuide("Nouveau client — ouverture d'un compte Xpress", GM + "enrolement")
                .choice("etape", "SINGLE", "Étape atteinte pendant l'appel", true,
                        "Application activée (code PIN créé):40:GREEN", "Téléchargée, activation à finir:15:PENDING", "Bloqué — code SMS non reçu:5:PENDING", "Bloqué — numéro différent du compte:5:YELLOW", "Pas encore téléchargée:0:PENDING")
                .reply("Bloqué — code SMS non reçu", "Attendre la fin du compte à rebours puis « Renvoyer le code » ; vérifier le réseau et que le numéro saisi est bien celui du compte.")
                .reply("Bloqué — numéro différent du compte", "Le numéro doit être mis à jour sur le compte : proposer un rendez-vous en agence (pièce d'identité) puis rappeler pour finir l'activation.")
                .section("Prise en main des fonctionnalités", "Montrer au client les fonctionnalités qui répondent à ses usages.",
                        "Faisons ensemble votre première opération pour que vous soyez à l'aise.")
                .sectionIf("smartphone", "eq", "Oui")
                .choice("demo", "MULTIPLE", "Fonctionnalités montrées au client", false,
                        "Voir son solde:5", "Transférer de l'argent:5", "Payer une facture:5", "Acheter du crédit:5", "Retrait sans carte (Xpress Cash):5", "Payer un marchand (EcobankPay):5", "Cartes prépayées et virtuelles:5", "Sécurité et code PIN:5")
                .optGuide("Voir son solde", GM + "solde").optGuide("Transférer de l'argent", GM + "transfert").optGuide("Payer une facture", GM + "facture")
                .optGuide("Acheter du crédit", GM + "credit").optGuide("Retrait sans carte (Xpress Cash)", GM + "xpress-cash").optGuide("Payer un marchand (EcobankPay)", GM + "ecobank-pay")
                .optGuide("Cartes prépayées et virtuelles", GM + "cartes").optGuide("Sécurité et code PIN", GM + "securite")
                .choice("premiere_op", "YES_NO", "Première opération réalisée pendant l'appel ?", false)
                .section("Objections anticipées", null, null)
                .sectionIf("joignable", "eq", "Oui, disponible")
                .choice("objection", "SINGLE", "Objection du client", false,
                        "Aucune objection", "Peur de la fraude / sécurité", "Pas assez d'espace sur le téléphone", "Préfère aller en agence", "Pas de connexion Internet régulière", "Trop compliqué pour moi", "Pas de smartphone")
                .reply("Peur de la fraude / sécurité", "Chaque connexion est protégée par votre code PIN à 6 chiffres (ou votre empreinte) et les opérations sensibles par un code SMS. Ecobank ne vous demandera jamais votre code : ne le donnez à personne.")
                .reply("Pas assez d'espace sur le téléphone", "L'application est légère ; on peut libérer de la place en supprimant quelques photos ou applications inutilisées. Je vous guide.")
                .reply("Préfère aller en agence", "L'agence reste là pour vous ; l'application vous évite simplement les déplacements pour les opérations courantes, même le dimanche.")
                .reply("Pas de connexion Internet régulière", "Quelques secondes de connexion suffisent pour une opération ; l'application consomme très peu de données.")
                .reply("Trop compliqué pour moi", "Je reste avec vous au téléphone et on le fait ensemble, écran par écran. Ensuite, vous pourrez me rappeler à tout moment.")
                .reply("Pas de smartphone", "Présenter Xpress Cash et les services disponibles en agence et au guichet automatique ; noter un rappel pour l'activation quand le client aura un smartphone. Ne jamais activer l'application sur le téléphone d'un tiers.")
                .section("Conclusion", null, "Merci {{client.prenom}}. Vous recevrez nos conseils par SMS ; n'hésitez pas à nous rappeler pour toute question.")
                .sectionIf("joignable", "eq", "Oui, disponible")
                .choice("issue", "SINGLE", "Issue de l'appel", true, "Client autonome sur l'application:30:GREEN", "Rappel planifié pour finir l'accompagnement:10:PENDING", "RDV en agence:10:YELLOW", "Refus:0:RED")
                .text("rappel", "DATE", "Date du rappel", false, null).validation("minDate", "today").showIf("issue", "eq", "Rappel planifié pour finir l'accompagnement")
                .outcome("GREEN", "Client activé sur Ecobank Mobile", "etape", "eq", "Application activée (code PIN créé)");
        return new Template("DIGITAL", "Outbound Digital — Ecobank Mobile", "Diagnostic digital, bénéfices, activation accompagnée avec le pas à pas officiel, prise en main de chaque fonctionnalité, objections anticipées.", "bi-phone-fill", f.form);
    }

    /** Télévente : parcours de proposition complet — besoin, produit, argumentaire, anticipation des objections, closing. */
    static Template televente() {
        F f = new F(55)
                .section("Prise de contact", null, HELLO)
                .choice("joignable", "SINGLE", "Le client est-il disponible ?", true, "Oui, disponible:0", "Rappeler plus tard:0:PENDING", "Mauvais numéro:0:RED")
                .section("Découverte du besoin", "Laisser parler le client : la proposition découle de ses réponses.",
                        "Pour vous proposer ce qui vous sera vraiment utile, puis-je vous poser quelques questions sur vos projets ?")
                .sectionIf("joignable", "eq", "Oui, disponible")
                .choice("profil", "SINGLE", "Profil du client", true, "Salarié (salaire domicilié chez Ecobank):20", "Salarié (salaire dans une autre banque):10",
                        "Commerçant / indépendant:10", "Fonctionnaire:20", "Étudiant / jeune actif:5", "Retraité:5")
                .choice("deja_client", "YES_NO", "Le client a-t-il déjà un compte Ecobank ?", true)
                .choice("besoins", "MULTIPLE", "Besoins exprimés", true, "Financer un projet:15", "Faire face à une dépense imprévue:15", "Épargner pour un objectif:10",
                        "Recevoir son salaire / ses revenus:10", "Payer sans espèces (en ligne, en voyage):10", "Gérer son argent à distance:10", "Envoyer de l'argent à ses proches:5")
                .pitch("piste_pret", "Piste : un prêt répond à ce besoin", "bi-lightbulb-fill",
                        "Le client veut financer un projet ou faire face à une dépense : proposer le prêt adapté à son profil.").showIf("besoins", "in", new String[]{"Financer un projet", "Faire face à une dépense imprévue"})
                .pitch("piste_compte", "Piste : ouverture ou complément de compte", "bi-lightbulb-fill",
                        "Pas encore client, ou besoin d'épargner / de domicilier ses revenus : proposer le type de compte adapté.").showIf("besoins", "in", new String[]{"Épargner pour un objectif", "Recevoir son salaire / ses revenus"})
                .pitch("piste_digital", "Piste : carte et services digitaux", "bi-lightbulb-fill",
                        "Besoin de payer sans espèces ou de gérer à distance : proposer la carte et Ecobank Mobile.").showIf("besoins", "in", new String[]{"Payer sans espèces (en ligne, en voyage)", "Gérer son argent à distance", "Envoyer de l'argent à ses proches"})
                .section("Produit proposé", null, "J'ai une solution qui correspond exactement à ce que vous venez de me dire…")
                .sectionIf("joignable", "eq", "Oui, disponible")
                .choice("produit", "SINGLE", "Produit présenté au client", true, "Prêt", "Ouverture de compte", "Carte bancaire", "Ecobank Mobile et services digitaux")
                // ── Prêts
                .section("Proposition — Prêt", "Conditions, taux et frais : selon la grille tarifaire en vigueur et l'étude du dossier.",
                        "Avec un prêt Ecobank, vous réalisez votre projet maintenant et vous remboursez à votre rythme, par mensualités fixes.")
                .sectionIf("produit", "eq", "Prêt")
                .choice("type_pret", "SINGLE", "Type de prêt", true, "Prêt personnel / consommation:10", "Avance sur salaire:10", "Prêt scolaire:10", "Prêt équipement / véhicule:10")
                .reply("Prêt personnel / consommation", "Pour tout projet personnel : montant et durée adaptés à votre capacité de remboursement, mensualités prélevées automatiquement.")
                .reply("Avance sur salaire", "Une solution rapide pour une dépense imprévue, remboursée sur vos prochains salaires domiciliés.")
                .reply("Prêt scolaire", "La rentrée financée sans puiser dans votre trésorerie, remboursée sur l'année scolaire.")
                .reply("Prêt équipement / véhicule", "Équipez-vous ou achetez votre véhicule, avec un remboursement étalé.")
                .pitch("avantages_pret", "Avantages à présenter", "bi-check2-circle",
                        "Mensualités fixes : le client sait exactement ce qu'il paie chaque mois.",
                        "Prélèvement automatique sur le compte : aucun oubli, aucun déplacement.",
                        "Étude du dossier rapide ; réponse communiquée par le conseiller.",
                        "Suivi du prêt et des échéances dans Ecobank Mobile.")
                .amount("montant", "Montant souhaité", false, 50_000, 100_000_000)
                .choice("duree", "DROPDOWN", "Durée souhaitée", false, "3 mois", "6 mois", "12 mois", "24 mois", "36 mois", "48 mois", "60 mois")
                .choice("revenus", "SINGLE", "Revenus domiciliés chez Ecobank", true, "Oui:25", "Non, prêt à domicilier son salaire:15", "Non:0")
                .reply("Non, prêt à domicilier son salaire", "La domiciliation du salaire facilite l'étude du prêt : proposer l'ouverture ou la mise à jour du compte dans la foulée.")
                // ── Comptes
                .section("Proposition — Ouverture de compte", "Choisir le compte selon le profil ; conditions selon la grille en vigueur.",
                        "Selon votre situation, voici le compte le plus adapté.")
                .sectionIf("produit", "eq", "Ouverture de compte")
                .choice("type_compte", "SINGLE", "Type de compte proposé", true, "Compte Xpress (100 % mobile):15", "Compte courant particulier:15", "Compte épargne:15", "Compte salaire (domiciliation):20")
                .reply("Compte Xpress (100 % mobile)", "Ouvert en quelques minutes depuis l'application Ecobank Mobile avec une pièce d'identité, sans passer en agence ; dépôts et retraits dans les points Xpress.")
                .reply("Compte courant particulier", "Le compte du quotidien : chéquier, carte de débit, virements et domiciliation des revenus.")
                .reply("Compte épargne", "Mettez de côté pour vos projets : votre épargne est séparée de vos dépenses courantes et rémunérée selon les conditions en vigueur.")
                .reply("Compte salaire (domiciliation)", "Recevez votre salaire chez Ecobank : accès facilité aux prêts et avances sur salaire, carte et application incluses selon l'offre.")
                .optGuide("Compte Xpress (100 % mobile)", GM + "enrolement")
                .pitch("avantages_compte", "Avantages à présenter", "bi-check2-circle",
                        "Accès au compte 24h/24 avec Ecobank Mobile (solde, virements, factures).",
                        "Carte de débit pour les retraits et paiements, dans le pays et à l'étranger.",
                        "Réseau Ecobank présent dans plus de 30 pays africains : transferts facilités entre comptes Ecobank.",
                        "Alertes et sécurité : code PIN, code SMS sur les opérations sensibles.")
                .choice("ouverture", "SINGLE", "Modalité d'ouverture", true, "Ouverture Xpress pendant l'appel (application):20:GREEN", "RDV en agence avec les pièces:15:YELLOW", "Envoi de la liste des pièces à fournir:5")
                .optGuide("Ouverture Xpress pendant l'appel (application)", GM + "enrolement")
                // ── Carte
                .section("Proposition — Carte bancaire", "Tarifs et plafonds selon la grille en vigueur.", "Une carte pour payer partout sans transporter d'espèces.")
                .sectionIf("produit", "eq", "Carte bancaire")
                .choice("type_carte", "SINGLE", "Carte proposée", true, "Carte de débit Visa:15", "Carte prépayée:10", "Carte virtuelle (achats en ligne):10")
                .reply("Carte de débit Visa", "Liée à votre compte : retraits aux guichets automatiques et paiements chez les commerçants et en ligne, dans le pays et à l'étranger.")
                .reply("Carte prépayée", "Vous chargez le montant de votre choix : idéale pour maîtriser un budget, pour un voyage ou pour un proche.")
                .reply("Carte virtuelle (achats en ligne)", "Créée en quelques secondes dans Ecobank Mobile, chargée du montant voulu : achats en ligne en toute sécurité.")
                .optGuide("Carte prépayée", GM + "cartes").optGuide("Carte virtuelle (achats en ligne)", GM + "cartes")
                .pitch("avantages_carte", "Avantages à présenter", "bi-check2-circle",
                        "Paiements en ligne et en magasin, retraits dans tout le réseau Visa.",
                        "Recharge et blocage immédiat depuis Ecobank Mobile en cas de perte.",
                        "Moins d'espèces sur soi : plus de sécurité.")
                // ── Digital
                .section("Proposition — Ecobank Mobile et services digitaux", null, "Avec l'application, votre banque tient dans votre poche, 24h/24.")
                .sectionIf("produit", "eq", "Ecobank Mobile et services digitaux")
                .guide(GM + "enrolement")
                .pitch("avantages_digital", "Avantages à présenter", "bi-phone-fill",
                        "Solde, virements, factures, crédit téléphonique et transferts MoMo depuis le téléphone.",
                        "Xpress Cash : retrait sans carte ; EcobankPay : paiement marchand par QR code.",
                        "Activation en quelques minutes, accompagnée pendant l'appel.")
                .choice("demo_digital", "MULTIPLE", "Fonctionnalités montrées", false, "Voir son solde", "Transférer de l'argent", "Payer une facture", "Acheter du crédit", "Retrait sans carte (Xpress Cash)")
                .optGuide("Voir son solde", GM + "solde").optGuide("Transférer de l'argent", GM + "transfert").optGuide("Payer une facture", GM + "facture")
                .optGuide("Acheter du crédit", GM + "credit").optGuide("Retrait sans carte (Xpress Cash)", GM + "xpress-cash")
                // ── Objections
                .section("Objections anticipées", "Choisir l'objection exprimée : la réponse conseillée s'affiche.", null)
                .sectionIf("joignable", "eq", "Oui, disponible")
                .choice("objection", "SINGLE", "Objection du client", false, "Aucune objection", "C'est trop cher / les frais", "Je dois réfléchir / en parler",
                        "J'ai déjà une banque", "Je n'ai pas le temps de passer en agence", "Je n'ai pas confiance / peur de l'endettement", "Pas de documents sous la main")
                .reply("C'est trop cher / les frais", "Ramener au bénéfice : temps et déplacements économisés, sécurité. Présenter le coût mensuel réel plutôt que le total, selon la grille en vigueur.")
                .reply("Je dois réfléchir / en parler", "C'est normal. Qu'est-ce qui vous ferait hésiter ? Je vous envoie le récapitulatif par SMS et je vous rappelle à la date de votre choix.")
                .reply("J'ai déjà une banque", "Ecobank peut compléter votre banque actuelle : application, transferts en Afrique, Xpress Cash… sans rien changer à vos habitudes.")
                .reply("Je n'ai pas le temps de passer en agence", "Justement : le compte Xpress s'ouvre depuis le téléphone, et pour le reste je prépare votre dossier pour un passage rapide sur rendez-vous.")
                .reply("Je n'ai pas confiance / peur de l'endettement", "La mensualité est calculée sur votre capacité de remboursement ; vous gardez la maîtrise et pouvez suivre chaque échéance dans l'application.")
                .reply("Pas de documents sous la main", "Je vous envoie la liste des pièces par SMS et on fixe un rappel ou un rendez-vous quand vous les avez.")
                // ── Closing
                .section("Conclusion et engagement", null, "Récapitulons : {{q:produit}}. Je vous propose de passer à l'étape suivante dès maintenant.")
                .sectionIf("joignable", "eq", "Oui, disponible")
                .choice("decision", "SINGLE", "Décision du client", true, "Accepte — souscription ou dossier lancé:40:GREEN", "RDV en agence:30:YELLOW", "Rappel à une date précise:10:PENDING", "Refus:0:RED")
                .text("rappel_date", "DATE", "Date du rappel", false, null).validation("minDate", "today").showIf("decision", "eq", "Rappel à une date précise")
                .choice("rebond", "MULTIPLE", "Autres produits à proposer au prochain contact (rebond)", false, "Prêt", "Compte épargne", "Carte bancaire", "Ecobank Mobile")
                .outcome("RED", "Refus du client", "decision", "eq", "Refus");
        return new Template("TELEVENTE", "Télévente — Parcours de proposition", "Découverte du besoin, produit recommandé (prêts, types de compte, cartes, digital), argumentaire, objections anticipées avec réponses, closing et rebond.", "bi-headset", f.form);
    }

    static Template savings() {
        F f = new F(55)
                .section("Prise de contact", null, HELLO)
                .choice("joignable", "SINGLE", "Le client est-il disponible ?", true, "Oui, disponible:0", "Rappeler plus tard:0:PENDING", "Mauvais numéro:0:RED")
                .section("Projet d'épargne", null, "Avez-vous un objectif d'épargne pour les mois à venir ?")
                .sectionIf("joignable", "eq", "Oui, disponible")
                .choice("objectif", "SINGLE", "Objectif principal", true, "Projet immobilier:20", "Études des enfants:20", "Épargne de précaution:15", "Retraite:15", "Aucun objectif:0")
                .amount("versement", "Versement mensuel envisagé", false, 5_000, 50_000_000).showIf("objectif", "neq", "Aucun objectif");
        f.question("priorites", "RANKING", "Classez vos priorités", false).put("help", "Du plus important au moins important");
        ArrayNode opts = ((ObjectNode) f.questions.get(f.questions.size() - 1)).putArray("options");
        for (String o : List.of("Rendement", "Disponibilité de l'argent", "Sécurité", "Simplicité")) opts.addObject().put("label", o);
        f.choice("decision", "SINGLE", "Décision", true, "Ouvre un compte épargne:40:GREEN", "RDV conseiller:30:YELLOW", "À rappeler:10:PENDING", "Pas intéressé:0:RED");
        return new Template("EPARGNE", "Épargne", "Objectif d'épargne, capacité de versement, priorités classées et décision.", "bi-piggy-bank-fill", f.form);
    }

    static Template kyc() {
        F f = new F(0)
                .section("Vérification d'identité", "Avant toute mise à jour, confirmer l'identité du client.", "Bonjour {{client.prenom}}, Ecobank met à jour les informations de ses clients. Pour votre sécurité, je vais vérifier quelques éléments.")
                .choice("identite", "YES_NO", "Identité confirmée (nom, date de naissance)", true)
                .section("Mise à jour des informations", null, null)
                .sectionIf("identite", "eq", "Oui")
                .text("telephone", "PHONE", "Téléphone principal", true, null).prefill("client.phone")
                .text("email", "EMAIL", "Adresse e-mail", false, null)
                .text("adresse", "LONG_TEXT", "Adresse de résidence", true, null)
                .choice("situation", "DROPDOWN", "Situation professionnelle", true, "Salarié du privé", "Fonctionnaire", "Commerçant / indépendant", "Étudiant", "Retraité", "Sans emploi")
                .text("employeur", "SHORT_TEXT", "Employeur", false, null).showIf("situation", "in", new String[]{"Salarié du privé", "Fonctionnaire"})
                .choice("piece", "SINGLE", "Pièce d'identité en cours de validité", true, "Oui, à jour", "Expirée — renouvellement à faire:0:YELLOW")
                .item_consent("consent", "Le client confirme l'exactitude des informations communiquées")
                .outcome("RED", "Identité non confirmée", "identite", "eq", "Non")
                .outcome("GREEN", "Dossier mis à jour", "consent", "eq", "true");
        return new Template("KYC", "Mise à jour KYC", "Vérification d'identité puis mise à jour des coordonnées et de la situation, avec consentement.", "bi-person-vcard-fill", f.form);
    }

    static Template insurance() {
        F f = new F(55)
                .section("Prise de contact", null, HELLO)
                .choice("joignable", "SINGLE", "Le client est-il disponible ?", true, "Oui, disponible:0", "Rappeler plus tard:0:PENDING", "Mauvais numéro:0:RED")
                .section("Couverture actuelle", null, "Êtes-vous aujourd'hui couvert en cas d'imprévu (santé, décès, études des enfants) ?")
                .sectionIf("joignable", "eq", "Oui, disponible")
                .choice("couverture", "MULTIPLE", "Assurances déjà détenues", false, "Santé", "Décès / prévoyance", "Éducation", "Aucune:20")
                .scale("famille", "NUMBER", "Nombre de personnes à charge", false, 0, 0, null, null)
                .validation("min", 0).validation("max", 20)
                .choice("produit", "SINGLE", "Produit présenté", true, "Assurance études:15", "Prévoyance décès:15", "Assurance santé:15", "Épargne retraite:15")
                .choice("decision", "SINGLE", "Décision", true, "Souscrit:40:GREEN", "RDV pour souscription:30:YELLOW", "À rappeler:10:PENDING", "Refus:0:RED");
        return new Template("ASSURANCE", "Assurance & prévoyance", "Couverture actuelle, personnes à charge, produit présenté et décision.", "bi-shield-check", f.form);
    }

    // ───────────── Génération sans IA : modèle le plus proche du besoin décrit ─────────────

    /** Modèle le plus proche d'une description libre (mots-clés), renommé d'après la demande. */
    public static ObjectNode closest(String prompt) {
        String p = fold(prompt);
        String code = p.matches(".*(pret|credit|financ|emprunt).*") ? "PRET"
                : p.matches(".*(carte|visa|mastercard|gab|dab).*") ? "CARTE"
                : p.matches(".*(satisf|nps|avis|enquete|sondage|recommand).*") ? "NPS"
                : p.matches(".*(televente|proposition|offre|compte).*") ? "TELEVENTE"
                : p.matches(".*(digital|mobile|appli|application|internet|omni|en ligne).*") ? "DIGITAL"
                : p.matches(".*(epargne|placement|depot a terme|dat).*") ? "EPARGNE"
                : p.matches(".*(kyc|mise a jour|identite|coordonnee|conformite).*") ? "KYC"
                : p.matches(".*(assurance|prevoyance|bancassurance).*") ? "ASSURANCE" : "PRET";
        return byCode(code).orElseThrow().form().deepCopy();
    }

    static String fold(String s) {
        return s == null ? "" : Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
