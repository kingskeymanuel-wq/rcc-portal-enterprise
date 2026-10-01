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
        return List.of(loan(), card(), satisfaction(), digital(), savings(), kyc(), insurance());
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

    static Template digital() {
        F f = new F(50)
                .section("Prise de contact", null, HELLO)
                .choice("joignable", "SINGLE", "Le client est-il disponible ?", true, "Oui, disponible:0", "Rappeler plus tard:0:PENDING", "Mauvais numéro:0:RED")
                .section("Équipement digital", null, "Utilisez-vous l'application Ecobank Mobile pour vos opérations ?")
                .sectionIf("joignable", "eq", "Oui, disponible")
                .choice("app", "SINGLE", "Ecobank Mobile", true, "Installée et utilisée:5", "Installée mais pas utilisée:15", "Pas installée:20")
                .choice("obstacle", "MULTIPLE", "Ce qui freine l'utilisation", false, "Ne sait pas l'installer:10", "Problème de connexion / code:10", "Méfiance / sécurité:10", "Préfère l'agence:5", "Pas de smartphone:0")
                .showIf("app", "in", new String[]{"Installée mais pas utilisée", "Pas installée"})
                .text("tel_smartphone", "PHONE", "Numéro du smartphone à activer", false, null).prefill("client.phone")
                .showIf("obstacle", "ncontains", "Pas de smartphone")
                .choice("accompagnement", "SINGLE", "Accompagnement réalisé pendant l'appel", true,
                        "Activation réussie pendant l'appel:40:GREEN", "Guidé, le client finalise seul:20", "RDV en agence pour activation:25:YELLOW", "Refus:0")
                .scale("aisance", "SCALE", "Aisance du client avec le digital", false, 5, 4, "Débutant", "Expert")
                .outcome("RED", "Refus de digitalisation", "accompagnement", "eq", "Refus");
        return new Template("DIGITAL", "Digitalisation (Ecobank Mobile)", "Équipement et activation des services digitaux, freins, accompagnement pendant l'appel.", "bi-phone-fill", f.form);
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
