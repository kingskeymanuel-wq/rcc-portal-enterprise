package com.ecobank.rccportal.raf.agent;

import com.ecobank.rccportal.dto.RafSuggestion;
import com.ecobank.rccportal.dto.RalphResultItem;
import com.ecobank.rccportal.raf.RafCatalog;
import com.ecobank.rccportal.raf.RafDocs;
import com.ecobank.rccportal.raf.RafDocs.BranchDoc;
import com.ecobank.rccportal.raf.RafModels.*;
import com.ecobank.rccportal.util.SearchText;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Agent Agences — agences Ecobank par pays / ville / nom (table BankBranches), avec adresse,
 * horaires, téléphone et lien carte. Le nom du responsable d'agence n'est jamais restitué.
 */
@Component
public class BranchAgent implements RafAgent {

    private final RafCatalog catalog;

    public BranchAgent(RafCatalog catalog) {
        this.catalog = catalog;
    }

    /** Disponibilité cartes / PIN cochée par les agences (optionnelle). */
    private com.ecobank.rccportal.service.CardAgencyStatusService cards;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCards(com.ecobank.rccportal.service.CardAgencyStatusService cards) {
        this.cards = cards;
    }

    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("\\(([A-Z]{1,3}\\d{1,4})\\)\\s*$");

    static String cardLine(com.ecobank.rccportal.dto.CardAgencyDtos.AgencyRow r) {
        java.util.function.Function<String, String> lbl = st -> "OK".equals(st) ? "✅ disponible" : "FAIBLE".equals(st) ? "⚠️ stock faible" : "❌ rupture";
        return "💳 Cartes : " + lbl.apply(r.cardStatus()) + " · Codes PIN : " + lbl.apply(r.pinStatus())
                + (r.cardTypes() != null && !r.cardTypes().isEmpty() ? " · " + String.join(", ", r.cardTypes()) : "")
                + (r.reportDate() != null ? " _(màj " + r.reportDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM")) + ")_" : "");
    }

    private com.ecobank.rccportal.service.AtmStatusService atms;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAtms(com.ecobank.rccportal.service.AtmStatusService atms) {
        this.atms = atms;
    }

    static String atmLine(com.ecobank.rccportal.service.AtmStatusService.AtmStatus a) {
        String icon = "EN_SERVICE".equals(a.status()) ? "✅" : "HORS_SERVICE".equals(a.status()) ? "❌" : "⚠️";
        return "🏧 GAB : " + icon + " " + com.ecobank.rccportal.service.AtmStatusService.label(a.status())
                + (a.gabTotal() != null && a.gabWorking() != null ? " (" + a.gabWorking() + "/" + a.gabTotal() + " en service)" : "")
                + (a.services() != null && !a.services().isEmpty() ? " · " + String.join(", ", a.services()).toLowerCase(Locale.ROOT).replace('_', ' ') : "")
                + (a.updatedAt() != null ? " _(màj " + a.updatedAt().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")) + ")_" : "");
    }

    public String id() { return "branch"; }

    public String label() { return "Agences Ecobank"; }

    public Set<RafIntent> intents() { return Set.of(RafIntent.BRANCH); }

    @Override
    public AgentAnswer answer(RafRequest request, double routerScore) {
        RafDocs.Snapshot data = catalog.snapshot();
        String country = request.entities().countryCode();
        String city = request.entities().city();
        List<BranchDoc> branches = data.branches();
        if (branches.isEmpty()) return AgentAnswer.notFound(id(), RafIntent.BRANCH, "aucune agence dans le portail");

        List<BranchDoc> matches;
        if (city != null) {
            matches = branches.stream().filter(b -> city.equalsIgnoreCase(b.city())).toList();
        } else if (country != null) {
            matches = branches.stream().filter(b -> country.equalsIgnoreCase(b.countryCode())).toList();
        } else {
            matches = Scoring.rank(request, branches, b -> new SearchText.Field[]{
                    SearchText.Field.of(b.name(), 3), SearchText.Field.of(b.address(), 1)}).stream().map(Scoring.Scored::doc).toList();
            if (matches.isEmpty()) {
                if (routerScore < 0.5) return AgentAnswer.notFound(id(), RafIntent.BRANCH, "aucun lieu précisé");
                // Question sur les agences sans lieu : on propose les pays qui ont des agences.
                Set<String> codes = new TreeSet<>();
                branches.forEach(b -> { if (b.countryCode() != null) codes.add(b.countryCode().toUpperCase(Locale.ROOT)); });
                List<RafSuggestion> chips = codes.stream().limit(8).map(code -> {
                    String label = data.countries().stream().filter(c -> c.code().equals(code)).map(RafDocs.CountryDoc::label)
                            .findFirst().orElse(code);
                    return new RafSuggestion(label, "agences " + label, null);
                }).toList();
                return new AgentAnswer(id(), RafIntent.BRANCH, 0.5, true, "Dans quel pays ou quelle ville cherches-tu une agence ?",
                        null, List.of(), chips, null, null, List.of("aucun lieu précisé — question de clarification"));
            }
        }
        if (matches.isEmpty()) return AgentAnswer.notFound(id(), RafIntent.BRANCH, "aucune agence pour ce lieu");

        String where = city != null ? city : country != null ? countryLabel(data, country) : null;
        StringBuilder md = new StringBuilder("**🏦 ").append(matches.size()).append(" agence(s)")
                .append(where != null ? " — " + where : "").append("**\n");
        java.util.Map<String, java.util.Map<String, com.ecobank.rccportal.dto.CardAgencyDtos.AgencyRow>> cardCache = new java.util.HashMap<>();
        for (BranchDoc b : matches.stream().limit(5).toList()) {
            md.append("\n• **").append(b.name()).append("**").append(b.city() != null ? " (" + b.city() + ")" : "");
            if (b.address() != null && !b.address().isBlank()) md.append("\n  ").append(b.address());
            if (b.openingHours() != null && !b.openingHours().isBlank()) md.append("\n  🕘 ").append(b.openingHours());
            if (b.phone() != null && !b.phone().isBlank()) md.append("\n  ☎ ").append(b.phone());
            java.util.regex.Matcher code = CODE.matcher(b.name() == null ? "" : b.name().toUpperCase(Locale.ROOT));
            if (cards != null && b.countryCode() != null && code.find()) {
                try {
                    var row = cardCache.computeIfAbsent(b.countryCode(), cards::currentByCode).get(code.group(1));
                    if (row != null) md.append("\n  ").append(cardLine(row));
                    if (atms != null) atms.current(b.countryCode(), code.group(1)).ifPresent(a -> md.append("\n  ").append(atmLine(a)));
                } catch (RuntimeException ignored) { /* disponibilité indisponible : réponse sans cette ligne */ }
            }
            if (b.latitude() != null && b.longitude() != null) {
                md.append("\n  🗺 https://www.openstreetmap.org/?mlat=").append(b.latitude()).append("&mlon=").append(b.longitude())
                        .append("#map=17/").append(b.latitude()).append("/").append(b.longitude());
            }
        }
        if (matches.size() > 5) md.append("\n\n… et ").append(matches.size() - 5).append(" autre(s) : voir l'onglet Agences de la Base de connaissances.");
        List<RalphResultItem> citations = matches.stream().limit(5)
                .map(b -> new RalphResultItem("BRANCH", (int) b.id(), b.name(), b.address())).toList();
        return new AgentAnswer(id(), RafIntent.BRANCH, city != null || country != null ? 0.9 : 0.6, true, md.toString(), null,
                citations, List.of(), null, null, List.of(matches.size() + " agence(s) trouvée(s)" + (where != null ? " pour " + where : "")));
    }

    private static String countryLabel(RafDocs.Snapshot data, String code) {
        return data.countries().stream().filter(c -> c.code().equalsIgnoreCase(code)).map(RafDocs.CountryDoc::label)
                .findFirst().orElse(code);
    }
}
