package com.ecobank.rccportal.raf.nlp;

import com.ecobank.rccportal.raf.RafCatalog;
import com.ecobank.rccportal.raf.RafDocs;
import com.ecobank.rccportal.raf.RafModels.RafEntities;
import com.ecobank.rccportal.util.SearchText;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repère dans la question : pays (code ISO ou nom usuel), ville d'agence, date (aujourd'hui,
 * demain, jour de la semaine, jj/mm), numéro d'étape, niveau N1/N2, et les champs d'un modèle
 * de message (nom du client, montant, référence) — ces derniers ne sont jamais journalisés.
 */
@Component
public class EntityExtractor {

    private static final Pattern STEP = Pattern.compile("\\b(?:etape|step|passo|paso)\\s*(\\d{1,2})\\b");
    private static final Pattern LEVEL = Pattern.compile("\\bn\\s?([123])\\b");
    private static final Pattern DATE = Pattern.compile("\\b(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{2,4}))?\\b");
    private static final Pattern AMOUNT = Pattern.compile("(\\d[\\d\\s.,]*\\d|\\d)\\s*(fcfa|f cfa|xof|xaf|cfa|eur|€|usd|\\$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME = Pattern.compile("\\b(?:pour|for|para)\\s+(M\\.|Mr\\.?|Mme|Mlle|Madame|Monsieur|Mrs\\.?|Ms\\.?|Sr\\.?|Sra\\.?)\\s+([A-ZÀ-Ý][\\p{L}'-]+(?:\\s+[A-ZÀ-Ý][\\p{L}'-]+)?)");
    private static final Pattern REFERENCE = Pattern.compile("\\br[ée]f(?:[ée]rence)?\\s*[:#n°]*\\s*([A-Za-z0-9-]{3,})", Pattern.CASE_INSENSITIVE);

    private static final Map<String, DayOfWeek> WEEKDAYS = Map.ofEntries(
            Map.entry("lundi", DayOfWeek.MONDAY), Map.entry("mardi", DayOfWeek.TUESDAY), Map.entry("mercredi", DayOfWeek.WEDNESDAY),
            Map.entry("jeudi", DayOfWeek.THURSDAY), Map.entry("vendredi", DayOfWeek.FRIDAY), Map.entry("samedi", DayOfWeek.SATURDAY),
            Map.entry("dimanche", DayOfWeek.SUNDAY), Map.entry("monday", DayOfWeek.MONDAY), Map.entry("tuesday", DayOfWeek.TUESDAY),
            Map.entry("wednesday", DayOfWeek.WEDNESDAY), Map.entry("thursday", DayOfWeek.THURSDAY), Map.entry("friday", DayOfWeek.FRIDAY),
            Map.entry("saturday", DayOfWeek.SATURDAY), Map.entry("sunday", DayOfWeek.SUNDAY));

    private final RafCatalog catalog;

    public EntityExtractor(RafCatalog catalog) {
        this.catalog = catalog;
    }

    public RafEntities extract(String question, LocalDate today) {
        String normalized = " " + SearchText.normalize(question) + " ";
        RafDocs.Snapshot data = catalog.snapshot();

        // Pays : alias le plus long trouvé (« guinee bissau » avant « guinee »), ou code ISO en majuscules.
        String country = null;
        int best = 0;
        for (RafDocs.CountryDoc c : data.countries()) {
            for (String alias : c.aliases()) {
                if (alias == null || alias.isBlank()) continue;
                if (normalized.contains(" " + alias + " ") && alias.length() > best) {
                    best = alias.length();
                    country = c.code();
                }
            }
        }
        if (country == null) {
            Matcher iso = Pattern.compile("\\b([A-Z]{2})\\b").matcher(question);
            while (iso.find()) {
                String code = iso.group(1);
                if (data.countries().stream().anyMatch(c -> c.code().equals(code))) {
                    country = code;
                    break;
                }
            }
        }

        String city = null;
        for (RafDocs.BranchDoc b : data.branches()) {
            String c = SearchText.normalize(b.city());
            if (!c.isBlank() && normalized.contains(" " + c + " ") && (city == null || c.length() > SearchText.normalize(city).length())) {
                city = b.city();
            }
        }

        LocalDate date = null;
        if (normalized.contains(" aujourd hui ") || normalized.contains(" today ") || normalized.contains(" hoje ") || normalized.contains(" hoy ")) {
            date = today;
        } else if (normalized.contains(" apres demain ")) {
            date = today.plusDays(2);
        } else if (normalized.contains(" demain ") || normalized.contains(" tomorrow ") || normalized.contains(" amanha ") || normalized.contains(" manana ")) {
            date = today.plusDays(1);
        } else if (normalized.contains(" hier ") || normalized.contains(" yesterday ")) {
            date = today.minusDays(1);
        } else {
            for (Map.Entry<String, DayOfWeek> e : WEEKDAYS.entrySet()) {
                if (normalized.contains(" " + e.getKey() + " ")) {
                    LocalDate d = today;
                    while (d.getDayOfWeek() != e.getValue()) d = d.plusDays(1);
                    date = d;
                    break;
                }
            }
            if (date == null) {
                Matcher m = DATE.matcher(question);
                if (m.find()) {
                    try {
                        int year = m.group(3) == null ? today.getYear()
                                : (m.group(3).length() == 2 ? 2000 + Integer.parseInt(m.group(3)) : Integer.parseInt(m.group(3)));
                        date = LocalDate.of(year, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
                    } catch (Exception ignored) {
                        // date invalide (ex. 31/02) — ignorée
                    }
                }
            }
        }

        Integer step = null;
        Matcher sm = STEP.matcher(normalized);
        if (sm.find()) step = Integer.parseInt(sm.group(1));

        String level = null;
        Matcher lm = LEVEL.matcher(normalized);
        if (lm.find()) level = "N" + lm.group(1);

        Map<String, String> slots = new LinkedHashMap<>();
        Matcher nm = NAME.matcher(question);
        if (nm.find()) slots.put("NOM", nm.group(1) + " " + nm.group(2));
        Matcher am = AMOUNT.matcher(question);
        if (am.find()) slots.put("MONTANT", am.group(1).trim() + " " + am.group(2).toUpperCase());
        Matcher rm = REFERENCE.matcher(question);
        if (rm.find()) slots.put("REFERENCE", rm.group(1));
        if (date != null) slots.put("DATE", String.format("%02d/%02d/%d", date.getDayOfMonth(), date.getMonthValue(), date.getYear()));

        return new RafEntities(country, city, date, step, level, Map.copyOf(slots));
    }
}
