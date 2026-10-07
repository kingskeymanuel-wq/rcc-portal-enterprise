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

    /** Reformulation naturelle de la synthèse (facultative : sans clé, synthèse locale par extraits). */
    private com.ecobank.rccportal.service.AnthropicClient writer;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setWriter(com.ecobank.rccportal.service.AnthropicClient writer) {
        this.writer = writer;
    }

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

    /** « l&#039;atropine », « &amp; »… : entités HTML des extraits remises en texte. */
    static String clean(String s) {
        if (s == null) return "";
        return s.replace("&#039;", "'").replace("&#39;", "'").replace("&apos;", "'").replace("&quot;", "\"").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * Garde seulement les résultats qui parlent vraiment de la question : la majorité de ses mots métier doit
     * apparaître dans le titre ou l'extrait (« nerveux », « client » isolés ne suffisent plus — fini les pages
     * Wikipédia sans rapport). Les pages officielles Ecobank passent en tête.
     */
    public static List<WebSearchResultItem> relevant(List<WebSearchResultItem> results, List<String> terms) {
        List<String> t = terms == null ? List.of() : terms.stream().filter(x -> x != null && x.length() >= 3).distinct().toList();
        if (t.isEmpty() || results == null) return List.of();
        int needed = Math.max(1, (int) Math.ceil(t.size() * 0.6));
        List<WebSearchResultItem> out = new java.util.ArrayList<>();
        for (WebSearchResultItem r : results) {
            String hay = " " + com.ecobank.rccportal.util.SearchText.normalize(clean(r.title()) + " " + clean(r.snippet())) + " ";
            long hits = t.stream().filter(term -> hay.contains(term)).count();
            if (hits >= needed) out.add(new WebSearchResultItem(clean(r.title()), clean(r.snippet()), r.url()));
        }
        out.sort(java.util.Comparator.comparing(r -> r.url() != null && r.url().toLowerCase(Locale.ROOT).contains("ecobank") ? 0 : 1));
        return out;
    }

    /**
     * Réponse rédigée comme par un conseiller : RAF lit les extraits et répond avec ses mots, sans renvoyer
     * de liens à ouvrir. Avec Claude configuré, la synthèse est reformulée (uniquement à partir des extraits,
     * question assainie) ; sinon, RAF assemble les phrases les plus pertinentes.
     */
    public String humanAnswer(String question, List<WebSearchResultItem> results, List<String> terms, String lang) {
        boolean fr = lang == null || lang.toLowerCase(Locale.ROOT).startsWith("fr");
        List<WebSearchResultItem> top = results.subList(0, Math.min(5, results.size()));
        boolean official = top.stream().anyMatch(r -> r.url() != null && r.url().toLowerCase(Locale.ROOT).contains("ecobank"));
        String written = null;
        if (writer != null && writer.isAvailable()) {
            StringBuilder extracts = new StringBuilder();
            for (int i = 0; i < top.size(); i++) {
                extracts.append("[").append(i + 1).append("] ").append(host(top.get(i).url()).replace("🔗 ", "")).append(" — ")
                        .append(top.get(i).title()).append(" : ").append(top.get(i).snippet()).append("\n");
            }
            try {
                written = writer.chat(
                        (fr ? "Tu es RAF, l'assistant des conseillers du centre de relation client Ecobank. Réponds en français, "
                            : "You are RAF, the assistant of Ecobank contact-centre agents. Answer in English, ")
                                + (fr ? "comme un collègue expérimenté : 2 à 5 phrases claires, sans liste de liens, sans URL, sans inventer. "
                                      + "Utilise UNIQUEMENT les extraits fournis. S'ils ne répondent pas vraiment à la question, dis-le simplement en une phrase."
                                      : "like an experienced colleague: 2 to 5 clear sentences, no list of links, no URL, no invention. "
                                      + "Use ONLY the extracts provided. If they do not really answer the question, say so in one sentence."),
                        (fr ? "Question : " : "Question: ") + dataProtection.sanitize(question) + "\n\n" + (fr ? "Extraits :\n" : "Extracts:\n") + extracts,
                        400, 15);
            } catch (RuntimeException e) {
                written = null; // indisponible : synthèse locale
            }
        }
        if (written == null || written.isBlank()) {
            StringBuilder all = new StringBuilder();
            for (WebSearchResultItem r : top) all.append(r.snippet()).append(r.snippet().endsWith(".") ? " " : ". ");
            String best = com.ecobank.rccportal.util.SearchText.bestSentences(all.toString(), terms, 3, 600);
            written = (fr ? "D'après ce que j'ai trouvé" + (official ? " sur le site officiel d'Ecobank" : "") + " : "
                          : "From what I found" + (official ? " on Ecobank's official website" : "") + ": ") + best.replaceAll("\\s+", " ").trim();
        }
        java.util.LinkedHashSet<String> hosts = new java.util.LinkedHashSet<>();
        for (WebSearchResultItem r : top) hosts.add(host(r.url()).replace("🔗 ", ""));
        return "🌐 " + written.trim() + "\n\n_" + (fr ? "Sources consultées : " : "Sources: ") + String.join(", ", hosts) + ". "
                + (fr ? "Information externe au portail : vérifiez-la avant de la communiquer au client — les procédures internes restent la référence."
                      : "External information: check it before sharing with the customer — internal procedures remain the reference.") + "_";
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
