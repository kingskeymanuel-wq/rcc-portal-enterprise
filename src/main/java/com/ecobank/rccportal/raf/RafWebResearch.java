package com.ecobank.rccportal.raf;

import com.ecobank.rccportal.dto.WebSearchResultItem;
import com.ecobank.rccportal.service.DataProtectionService;
import com.ecobank.rccportal.service.WebSearchClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAF au-delà du portail : quand les contenus internes ne suffisent pas (ou quand l'agent le
 * demande — « cherche sur le web … »), RAF interroge le web (pages officielles Ecobank
 * d'abord, puis web général, puis Wikipédia) et présente un résumé SOURCÉ, toujours signalé
 * comme externe et à vérifier.
 *
 * <p>Confidentialité : seule la question ASSAINIE (sans numéro de compte, carte, téléphone,
 * e-mail… — {@link DataProtectionService}) quitte le réseau, jamais les données client.</p>
 */
@Component
public class RafWebResearch {

    /** « cherche sur internet les frais swift », « recherche google : … », « … sur le web ». */
    private static final Pattern EXPLICIT_PREFIX = Pattern.compile(
            "^(?:raf[, ]+)?(?:peux[- ]tu |pourrais[- ]tu |tu peux )?(?:cherche|recherche|regarde|trouve|verifie|vérifie|search|look up)(?:[- ]moi)?"
                    + "\\s+(?:sur|dans|on)\\s+(?:le\\s+|l')?(?:web|internet|google|net|online)\\s*[:,-]?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_SUFFIX = Pattern.compile(
            "^(.+?)\\s+(?:sur|dans|on)\\s+(?:le\\s+|l')?(?:web|internet|google)\\s*[?.!]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_WORD = Pattern.compile("^(?:web|internet|google)\\s*[:]\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    private final WebSearchClient client;
    private final DataProtectionService dataProtection;

    public RafWebResearch(WebSearchClient client, DataProtectionService dataProtection) {
        this.client = client;
        this.dataProtection = dataProtection;
    }

    public boolean available() {
        return client.isConfigured();
    }

    /** Question explicitement adressée au web → la requête à lancer, sinon {@code null}. */
    public static String explicitQuery(String question) {
        if (question == null) return null;
        String q = question.trim();
        for (Pattern p : List.of(EXPLICIT_PREFIX, EXPLICIT_WORD, EXPLICIT_SUFFIX)) {
            Matcher m = p.matcher(q);
            if (m.matches()) {
                String query = m.group(1).trim().replaceAll("[?.!]+$", "").trim();
                if (query.length() >= 2) return query;
            }
        }
        return null;
    }

    public List<WebSearchResultItem> search(String question) {
        String safe = dataProtection.sanitize(question == null ? "" : question).trim();
        if (safe.isEmpty()) return List.of();
        return client.searchWide(safe);
    }

    /** Réponse lisible : synthèse des meilleurs extraits + sources numérotées. */
    public static String markdown(String question, List<WebSearchResultItem> results, boolean nothingInternal, String lang) {
        boolean fr = lang == null || lang.toLowerCase(Locale.ROOT).startsWith("fr");
        StringBuilder md = new StringBuilder();
        if (nothingInternal) {
            md.append(fr ? "🌐 Je n'ai rien de fiable dans le portail sur ce point, voici ce que j'ai trouvé **sur le web** :"
                         : "🌐 Nothing reliable in the portal on this, here is what I found **on the web**:");
        } else {
            md.append(fr ? "🌐 Voici ce que j'ai trouvé **sur le web** pour « " + question + " » :"
                         : "🌐 Here is what I found **on the web** for “" + question + "”:");
        }
        // En bref : les extraits les plus parlants (2 sites différents max) ; les liens cliquables
        // sont affichés en cartes « Source web » sous la réponse.
        StringBuilder brief = new StringBuilder();
        java.util.Set<String> hosts = new java.util.HashSet<>();
        for (WebSearchResultItem r : results) {
            if (hosts.size() >= 2) break;
            if (r.snippet() == null || r.snippet().isBlank() || !hosts.add(host(r.url()))) continue;
            boolean official = r.url() != null && r.url().toLowerCase(Locale.ROOT).contains("ecobank.com");
            brief.append("\n\n• ").append(r.snippet()).append(" _(").append(host(r.url()).replace("🔗 ", ""))
                    .append(official ? (fr ? ", site officiel Ecobank ✅" : ", official Ecobank site ✅") : "").append(")_");
        }
        if (brief.length() > 0) md.append("\n\n**").append(fr ? "En bref" : "In short").append(" :**").append(brief);
        md.append("\n\n").append(fr ? "📎 Sources ci-dessous (" + results.size() + ") — cliquez pour ouvrir."
                                         : "📎 Sources below (" + results.size() + ") — click to open.");
        md.append("\n\n_").append(fr
                ? "Source externe : vérifiez l'information avant de la communiquer au client — les procédures internes du portail restent la référence."
                : "External source: check before sharing with the customer — the portal's internal procedures remain the reference.").append("_");
        return md.toString();
    }

    private static String host(String url) {
        try {
            String h = java.net.URI.create(url).getHost();
            return h == null ? url : "🔗 " + h.replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return url;
        }
    }
}
