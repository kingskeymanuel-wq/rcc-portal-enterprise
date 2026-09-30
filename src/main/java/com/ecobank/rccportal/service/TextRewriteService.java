package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Réécriture de texte pour les agents (Traducteur &amp; correcteur → onglet Réécriture) :
 * « professionnel », « courtois », « concis » ou « plus simple ».
 * <ul>
 *   <li>Avec la clé Anthropic configurée (quality.ai.anthropic-key) : reformulation par l'IA, qui garde
 *       le sens, les montants, les dates et les références à l'identique ;</li>
 *   <li>sinon, réécriture locale à base de règles, sans IA : fautes corrigées (LanguageTool, meilleure
 *       suggestion), abréviations et tournures familières remplacées, majuscules et ponctuation,
 *       formules de politesse — le texte reste celui de l'agent, en plus propre.</li>
 * </ul>
 */
@Service
public class TextRewriteService {

    public record RewriteResult(String text, String style, String engine, List<String> changes) {}

    public static final int MAX_LENGTH = 5000;

    static final Map<String, String> STYLES = new LinkedHashMap<>();
    static {
        STYLES.put("professionnel", "professionnel et précis, adapté à la relation client bancaire");
        STYLES.put("courtois", "chaleureux et courtois (formules de politesse, empathie), sans excès");
        STYLES.put("concis", "plus court et direct : supprime les répétitions et le superflu");
        STYLES.put("simple", "plus simple à comprendre : phrases courtes, mots courants");
    }

    /** Abréviations et tournures familières fréquentes dans les échanges clients (français). */
    static final String[][] FAMILIAR_FR = {
            {"svp", "s'il vous plaît"}, {"stp", "s'il vous plaît"}, {"slvp", "s'il vous plaît"},
            {"bcp", "beaucoup"}, {"pb", "problème"}, {"pbs", "problèmes"}, {"pr", "pour"}, {"ds", "dans"},
            {"tjrs", "toujours"}, {"tjs", "toujours"}, {"qqn", "quelqu'un"}, {"qqch", "quelque chose"},
            {"dsl", "désolé"}, {"mtn", "maintenant"}, {"rdv", "rendez-vous"}, {"infos", "informations"},
            {"info", "information"}, {"ok", "d'accord"}, {"okay", "d'accord"}, {"cc", "Bonjour"}, {"slt", "Bonjour"},
            {"salut", "Bonjour"}, {"coucou", "Bonjour"}, {"mr", "Monsieur"}, {"mme", "Madame"}, {"mlle", "Madame"},
            {"cad", "c'est-à-dire"}, {"c-a-d", "c'est-à-dire"}, {"asap", "dans les plus brefs délais"},
            {"ns", "nous"}, {"ya", "il y a"}, {"y'a", "il y a"}, {"chui", "je suis"}, {"jsuis", "je suis"},
            {"tkt", "ne vous inquiétez pas"}, {"merci bcp", "merci beaucoup"}, {"gratos", "gratuit"}, {"boulot", "travail"},
            {"bosser", "travailler"}, {"fric", "argent"}};

    /** Registre soutenu (styles professionnel et courtois). */
    static final String[][] FORMAL_FR = {
            {"on va", "nous allons"}, {"on vous", "nous vous"}, {"on a", "nous avons"}, {"on est", "nous sommes"},
            {"on peut", "nous pouvons"}, {"on doit", "nous devons"}, {"ça", "cela"}, {"ca va", "cela va"}};

    /** Tournures superflues retirées en mode « concis ». */
    static final String[] FILLERS_FR = {"en fait ", "en effet, ", "du coup,? ", "je me permets de vous informer que ",
            "nous tenons à vous informer que ", "nous vous informons que ", "il faut savoir que ", "à vrai dire,? ",
            "comme vous le savez,? ", "sachez que ", "je vous informe que "};

    private final LocalSpellCheckClient spellCheck;
    private final AnthropicClient anthropic;

    public TextRewriteService(LocalSpellCheckClient spellCheck, AnthropicClient anthropic) {
        this.spellCheck = spellCheck;
        this.anthropic = anthropic;
    }

    public List<Map<String, String>> styles() {
        List<Map<String, String>> out = new ArrayList<>();
        STYLES.forEach((k, v) -> out.add(Map.of("code", k, "label", v)));
        return out;
    }

    public boolean aiAvailable() {
        return anthropic.isConfigured();
    }

    public RewriteResult rewrite(String text, String lang, String style, boolean allowAi) {
        if (text == null || text.isBlank()) throw ApiException.badRequest("Saisissez le texte à réécrire.");
        if (text.length() > MAX_LENGTH) throw ApiException.badRequest("Texte trop long (" + text.length() + " caractères, maximum " + MAX_LENGTH + ").");
        String st = style == null || !STYLES.containsKey(style.trim().toLowerCase(Locale.ROOT)) ? "professionnel" : style.trim().toLowerCase(Locale.ROOT);
        String lg = lang == null || lang.isBlank() ? "fr" : lang.trim();
        if (allowAi && anthropic.isConfigured()) {
            try {
                return aiRewrite(text, lg, st);
            } catch (ApiException e) {
                // IA indisponible (réseau, quota) : la réécriture locale prend le relais plutôt qu'une erreur.
                RewriteResult local = localRewrite(text, lg, st);
                List<String> changes = new ArrayList<>(local.changes());
                changes.add(0, "IA indisponible (" + e.getMessage() + ") — réécriture locale utilisée.");
                return new RewriteResult(local.text(), st, local.engine(), changes);
            }
        }
        return localRewrite(text, lg, st);
    }

    // ───────────── IA (Anthropic, client du projet) ─────────────

    private RewriteResult aiRewrite(String text, String lang, String style) {
        String language = lang.startsWith("en") ? "anglais" : lang.startsWith("fr") || "auto".equals(lang) ? "la langue du texte" : lang;
        String system = """
                Tu réécris des messages rédigés par des conseillers du centre de relation client d'une banque (Ecobank).
                Réécris le texte fourni en style %s, en %s.
                Règles : garde exactement le sens, les montants, les dates, les numéros, les références, les noms et les
                engagements ; n'invente aucune information, aucune promesse et aucun délai ; corrige l'orthographe et la
                grammaire ; garde le vouvoiement avec le client. Réponds uniquement par le texte réécrit, sans commentaire,
                sans guillemets ni titre.""".formatted(STYLES.get(style), language);
        String out = anthropic.chat(system, text, 2000, 30).trim();
        if (out.isEmpty()) throw ApiException.serviceUnavailable("réponse vide");
        return new RewriteResult(out, style, "IA (Anthropic)", List.of("Texte reformulé en style « " + style + " »."));
    }

    // ───────────── Règles locales (sans IA) ─────────────

    RewriteResult localRewrite(String text, String lang, String style) {
        List<String> changes = new ArrayList<>();
        boolean french = !lang.toLowerCase(Locale.ROOT).startsWith("en");
        String t = normalizeSpaces(text);

        // 1. Abréviations et tournures familières (français).
        if (french) {
            int n = 0;
            List<String[]> rules = new ArrayList<>(Arrays.asList(FAMILIAR_FR));
            if ("professionnel".equals(style) || "courtois".equals(style)) rules.addAll(Arrays.asList(FORMAL_FR));
            for (String[] f : rules) {
                Matcher m = Pattern.compile("(?iu)(?<![\\p{L}'’])" + Pattern.quote(f[0]) + "(?![\\p{L}'’])").matcher(t);
                StringBuilder sb = new StringBuilder();
                boolean any = false;
                while (m.find()) {
                    any = true;
                    n++;
                    m.appendReplacement(sb, Matcher.quoteReplacement(f[1]));
                }
                m.appendTail(sb);
                if (any) t = sb.toString();
            }
            if (n > 0) changes.add(n + " abréviation(s) ou tournure(s) familière(s) remplacée(s).");
        }

        // 2. Fautes : meilleure suggestion de LanguageTool pour chaque erreur (de la fin vers le début).
        try {
            var result = spellCheck.check(t, lang);
            List<LocalSpellCheckClient.Issue> issues = new ArrayList<>(result.issues());
            issues.sort(Comparator.comparingInt(LocalSpellCheckClient.Issue::offset).reversed());
            int fixed = 0;
            for (var i : issues) {
                if (i.suggestions() == null || i.suggestions().isEmpty() || "style".equals(i.type())) continue;
                if (i.offset() < 0 || i.offset() + i.length() > t.length()) continue;
                t = t.substring(0, i.offset()) + i.suggestions().get(0) + t.substring(i.offset() + i.length());
                fixed++;
            }
            if (fixed > 0) changes.add(fixed + " faute(s) corrigée(s).");
        } catch (RuntimeException e) {
            changes.add("Correcteur indisponible : fautes non corrigées.");
        }

        // 3. Concision : tournures superflues.
        if ("concis".equals(style) && french) {
            int n = 0;
            for (String f : FILLERS_FR) {
                Matcher m = Pattern.compile("(?iu)\\b" + f).matcher(t);
                if (m.find()) { t = m.replaceAll(""); n++; }
            }
            t = t.replaceAll("(?iu)\\b(\\p{L}+)( \\1\\b)+", "$1"); // mots répétés (« le le »)
            if (n > 0) changes.add(n + " tournure(s) superflue(s) retirée(s).");
        }

        // 4. Plus simple : une idée par phrase (coupe aux points-virgules et aux longues incises).
        if ("simple".equals(style)) {
            String before = t;
            t = t.replaceAll("\\s*;\\s*", ". ");
            if (!t.equals(before)) changes.add("Phrases longues découpées.");
        }

        // 5. Majuscules en début de phrase, ponctuation finale, espaces.
        String cap = capitalizeSentences(t);
        if (!cap.equals(t)) changes.add("Majuscules en début de phrase.");
        t = cap;
        if (!t.isBlank() && !t.trim().matches("(?s).*[.!?…:)]$")) { t = t.trim() + "."; changes.add("Point final ajouté."); }
        if (french) t = frenchPunctuation(t);

        // 6. Formules de politesse (courtois / professionnel).
        if (french && ("courtois".equals(style) || "professionnel".equals(style))) {
            String low = t.toLowerCase(Locale.ROOT).trim();
            if (!low.matches("(?s)^(bonjour|bonsoir|madame|monsieur|cher|chère|mesdames).*")) {
                t = "Bonjour,\n\n" + t.trim();
                changes.add("Salutation ajoutée.");
            }
            if (!low.matches("(?s).*(cordialement|salutations|bonne (journée|soirée)|à votre (disposition|écoute)|restons à votre)\\W*$")) {
                t = t.trim() + ("courtois".equals(style)
                        ? "\n\nNous restons à votre écoute et vous remercions pour votre confiance.\n\nCordialement."
                        : "\n\nCordialement.");
                changes.add("Formule de politesse ajoutée.");
            }
        }
        if (changes.isEmpty()) changes.add("Texte déjà propre : aucune modification nécessaire.");
        return new RewriteResult(t.trim(), style, "Règles locales (sans IA)", changes);
    }

    static String normalizeSpaces(String t) {
        return t.replace(' ', ' ').replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    static String capitalizeSentences(String t) {
        StringBuilder sb = new StringBuilder(t);
        boolean start = true;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (start && Character.isLetter(c)) {
                sb.setCharAt(i, Character.toUpperCase(c));
                start = false;
            } else if (c == '.' || c == '!' || c == '?' || c == '\n') {
                // « 1.5 », « www.ecobank.com », « M. Koffi » : pas une fin de phrase.
                boolean realEnd = c == '\n' || i + 1 >= sb.length() || Character.isWhitespace(sb.charAt(i + 1));
                if (c == '.' && i >= 1 && i + 1 < sb.length() && Character.isDigit(sb.charAt(i + 1))) realEnd = false;
                if (realEnd) start = true;
            } else if (Character.isLetterOrDigit(c)) {
                start = false;
            }
        }
        return sb.toString();
    }

    /** Typographie française : espace avant « ? ! : ; », aucune avant « . , ». */
    static String frenchPunctuation(String t) {
        return t.replaceAll("\\s+([.,])", "$1")
                .replaceAll("(?<=[\\p{L}\\d)»])([?!;])", " $1")
                .replaceAll("(?<=[\\p{L}\\d)»]):(?!//|\\d)", " :")
                .replaceAll(" {2,}", " ");
    }
}
