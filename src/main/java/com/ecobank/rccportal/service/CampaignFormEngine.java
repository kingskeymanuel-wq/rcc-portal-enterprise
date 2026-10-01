package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.CampaignFieldDto;
import com.ecobank.rccportal.util.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Formulaire de campagne Outbound (version 2) — la référence serveur, indépendante de l'écran.
 *
 * <pre>
 * { "version": 2,
 *   "settings": { "showProgress": true, "qualifiedThreshold": 60 },
 *   "sections": [ { "id", "title", "description", "script", "visibleIf",
 *                   "questions": [ { "id", "type", "label", "help", "required", "options": [{ "id", "label", "score", "outcome" }],
 *                                    "allowOther", "validation": { "min", "max", "minLength", "maxLength", "pattern", "message",
 *                                    "minDate", "maxDate", "minSelect", "maxSelect" }, "scale": { "min", "max", "minLabel", "maxLabel" },
 *                                    "rows": [{ "id", "label" }], "weight", "prefill", "placeholder", "visibleIf" } ] } ],
 *   "outcomes": [ { "when": { "logic": "all|any", "rules": [{ "q", "op", "value" }] }, "status", "label" } ] }
 * </pre>
 *
 * Réponses : { idQuestion: "valeur" } — une liste (choix multiples, classement) ou une grille (ligne → colonne) est
 * transmise en JSON dans la valeur, ce qui garde le stockage existant (CampaignContacts.AnswersJson) inchangé.
 * Le serveur recalcule tout : questions affichées (logique conditionnelle), contrôles, score du lead et issue d'appel
 * suggérée — rien n'est cru du navigateur.
 */
public final class CampaignFormEngine {

    private CampaignFormEngine() {}

    static final ObjectMapper JSON = new ObjectMapper();

    /** Types de question proposés dans le concepteur. */
    public static final List<String> TYPES = List.of("SHORT_TEXT", "LONG_TEXT", "SINGLE", "MULTIPLE", "DROPDOWN", "YES_NO", "SCALE",
            "RATING", "NPS", "NUMBER", "AMOUNT", "DATE", "TIME", "PHONE", "EMAIL", "MATRIX", "RANKING", "CONSENT", "STATEMENT");

    static final Set<String> CHOICE_TYPES = Set.of("SINGLE", "MULTIPLE", "DROPDOWN", "RANKING");
    static final Set<String> OPS = Set.of("eq", "neq", "in", "nin", "contains", "ncontains", "gt", "gte", "lt", "lte", "empty", "notempty");
    static final Set<String> STATUSES = Set.of("PENDING", "GREEN", "RED", "YELLOW");

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");

    // ───────────────────────────── Structure ─────────────────────────────

    /** Formulaire contrôlé et complété (identifiants manquants, bornes) ; message clair pour toute incohérence. */
    public static ObjectNode normalize(JsonNode raw) {
        if (raw == null || raw.isNull() || !raw.isObject()) throw ApiException.badRequest("Formulaire invalide.");
        ObjectNode form = ((ObjectNode) raw).deepCopy();
        form.put("version", 2);
        if (!form.has("settings") || !form.get("settings").isObject()) form.putObject("settings");
        JsonNode sections = form.get("sections");
        if (sections == null || !sections.isArray() || sections.isEmpty()) throw ApiException.badRequest("Le formulaire doit contenir au moins une section.");
        Set<String> ids = new HashSet<>();
        List<String> seenQuestions = new ArrayList<>();
        int sIdx = 0, qCount = 0;
        for (JsonNode sn : sections) {
            sIdx++;
            if (!sn.isObject()) throw ApiException.badRequest("Section " + sIdx + " invalide.");
            ObjectNode s = (ObjectNode) sn;
            String sid = uniqueId(s, "s" + sIdx, ids);
            if (text(s, "title").isEmpty()) s.put("title", "Section " + sIdx);
            checkCondition(s.get("visibleIf"), seenQuestions, "la section « " + text(s, "title") + " »");
            JsonNode qs = s.get("questions");
            if (qs == null || !qs.isArray()) { s.putArray("questions"); continue; }
            int qIdx = 0;
            for (JsonNode qn : qs) {
                qIdx++;
                if (!qn.isObject()) throw ApiException.badRequest("Question " + qIdx + " de la section « " + text(s, "title") + " » invalide.");
                ObjectNode q = (ObjectNode) qn;
                String qid = uniqueId(q, sid + "q" + qIdx, ids);
                String type = text(q, "type").toUpperCase(Locale.ROOT);
                if (!TYPES.contains(type)) throw ApiException.badRequest("Type de question inconnu : « " + text(q, "type") + " ».");
                q.put("type", type);
                String label = text(q, "label");
                if (label.isEmpty()) throw ApiException.badRequest("Une question de la section « " + text(s, "title") + " » n'a pas d'intitulé.");
                checkCondition(q.get("visibleIf"), seenQuestions, "la question « " + label + " »");
                if (CHOICE_TYPES.contains(type) || "MATRIX".equals(type)) {
                    ArrayNode opts = normalizeOptions(q.get("options"), qid + "o");
                    if (opts.size() < ("RANKING".equals(type) ? 2 : 1)) throw ApiException.badRequest("La question « " + label + " » n'a pas de choix.");
                    q.set("options", opts);
                }
                if ("YES_NO".equals(type) && (!q.has("options") || !q.get("options").isArray() || q.get("options").isEmpty())) {
                    ArrayNode yn = q.putArray("options");
                    yn.addObject().put("id", "yes").put("label", "Oui").put("score", 0);
                    yn.addObject().put("id", "no").put("label", "Non").put("score", 0);
                } else if ("YES_NO".equals(type)) {
                    q.set("options", normalizeOptions(q.get("options"), qid + "o"));
                }
                if ("MATRIX".equals(type)) {
                    ArrayNode rows = normalizeOptions(q.get("rows"), qid + "r");
                    if (rows.isEmpty()) throw ApiException.badRequest("La grille « " + label + " » n'a pas de ligne.");
                    q.set("rows", rows);
                }
                if ("SCALE".equals(type)) {
                    ObjectNode sc = q.has("scale") && q.get("scale").isObject() ? (ObjectNode) q.get("scale") : q.putObject("scale");
                    int min = sc.path("min").asInt(1), max = sc.path("max").asInt(5);
                    if (min < 0 || max > 10 || min >= max) throw ApiException.badRequest("Échelle de « " + label + " » : de 0 ou 1 jusqu'à 10 au plus.");
                    sc.put("min", min).put("max", max);
                }
                if ("RATING".equals(type)) {
                    ObjectNode sc = q.has("scale") && q.get("scale").isObject() ? (ObjectNode) q.get("scale") : q.putObject("scale");
                    int max = Math.max(3, Math.min(10, sc.path("max").asInt(5)));
                    sc.put("min", 1).put("max", max);
                }
                JsonNode v = q.get("validation");
                if (v != null && v.has("pattern") && !v.get("pattern").asText("").isBlank()) {
                    try { Pattern.compile(v.get("pattern").asText()); }
                    catch (PatternSyntaxException e) { throw ApiException.badRequest("Format attendu de « " + label + " » invalide (expression régulière)."); }
                }
                if (!"STATEMENT".equals(type)) seenQuestions.add(qid);
                qCount++;
            }
        }
        if (qCount > 300) throw ApiException.badRequest("300 questions au plus par formulaire.");
        JsonNode outcomes = form.get("outcomes");
        if (outcomes != null && outcomes.isArray()) {
            for (JsonNode o : outcomes) {
                String st = o.path("status").asText("").toUpperCase(Locale.ROOT);
                if (!STATUSES.contains(st)) throw ApiException.badRequest("Issue d'appel inconnue dans les règles : « " + o.path("status").asText() + " ».");
                ((ObjectNode) o).put("status", st);
                checkCondition(o.get("when"), seenQuestions, "une règle d'issue d'appel");
            }
        } else {
            form.putArray("outcomes");
        }
        return form;
    }

    private static ArrayNode normalizeOptions(JsonNode raw, String prefix) {
        ArrayNode out = JSON.createArrayNode();
        if (raw == null || !raw.isArray()) return out;
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (JsonNode o : raw) {
            i++;
            ObjectNode on;
            if (o.isTextual()) { on = JSON.createObjectNode(); on.put("label", o.asText()); }
            else if (o.isObject()) on = ((ObjectNode) o).deepCopy();
            else continue;
            String label = text(on, "label");
            if (label.isEmpty() || !seen.add(label.toLowerCase(Locale.ROOT))) continue;
            if (text(on, "id").isEmpty()) on.put("id", prefix + i);
            if (on.has("outcome")) {
                String st = on.get("outcome").asText("").toUpperCase(Locale.ROOT);
                if (STATUSES.contains(st)) on.put("outcome", st); else on.remove("outcome");
            }
            out.add(on);
        }
        return out;
    }

    private static String uniqueId(ObjectNode node, String fallback, Set<String> ids) {
        String id = text(node, "id").replaceAll("[^A-Za-z0-9_\\-]", "");
        if (id.isEmpty()) id = fallback;
        String base = id;
        int n = 2;
        while (!ids.add(id)) id = base + "_" + n++;
        node.put("id", id);
        return id;
    }

    /** Une condition ne porte que sur une question placée AVANT : aucun cycle possible. */
    private static void checkCondition(JsonNode cond, List<String> earlier, String where) {
        if (cond == null || cond.isNull()) return;
        JsonNode rules = cond.get("rules");
        if (rules == null || !rules.isArray()) return;
        for (JsonNode r : rules) {
            String q = r.path("q").asText("");
            if (!earlier.contains(q)) throw ApiException.badRequest("La condition de " + where + " doit porter sur une question placée avant elle.");
            if (!OPS.contains(r.path("op").asText("eq"))) throw ApiException.badRequest("Opérateur de condition inconnu pour " + where + ".");
        }
    }

    // ───────────────────────────── Compatibilité ─────────────────────────────

    /** Ancienne liste de questions (FieldsJson) → formulaire v2 d'une section. */
    public static ObjectNode fromLegacy(List<CampaignFieldDto> fields) {
        ObjectNode form = JSON.createObjectNode();
        form.put("version", 2);
        form.putObject("settings").put("showProgress", true);
        ObjectNode s = form.putArray("sections").addObject();
        s.put("id", "s1").put("title", "Questionnaire");
        ArrayNode qs = s.putArray("questions");
        for (CampaignFieldDto f : fields == null ? List.<CampaignFieldDto>of() : fields) {
            if (f == null || f.label() == null || f.label().isBlank()) continue;
            ObjectNode q = qs.addObject();
            q.put("id", f.id() == null || f.id().isBlank() ? "q" + qs.size() : f.id());
            q.put("label", f.label());
            q.put("required", f.required());
            String t = f.type() == null ? "TEXT" : f.type().toUpperCase(Locale.ROOT);
            q.put("type", switch (t) {
                case "TEXTAREA" -> "LONG_TEXT";
                case "SELECT" -> "DROPDOWN";
                case "RADIO" -> "SINGLE";
                case "DATE" -> "DATE";
                case "TIME" -> "TIME";
                default -> "SHORT_TEXT";
            });
            if (f.options() != null && !f.options().isEmpty()) {
                ArrayNode opts = q.putArray("options");
                int i = 0;
                for (String o : f.options()) if (o != null && !o.isBlank()) opts.addObject().put("id", q.get("id").asText() + "o" + (++i)).put("label", o);
                if (opts.isEmpty() && ("DROPDOWN".equals(q.get("type").asText()) || "SINGLE".equals(q.get("type").asText()))) q.put("type", "SHORT_TEXT");
            } else if (Set.of("DROPDOWN", "SINGLE").contains(q.get("type").asText())) {
                q.put("type", "SHORT_TEXT");
            }
        }
        form.putArray("outcomes");
        return form;
    }

    /** Formulaire v2 → liste plate (ancien format) : import de fichiers et écrans existants continuent de fonctionner. */
    public static List<CampaignFieldDto> toLegacyFields(JsonNode form) {
        List<CampaignFieldDto> out = new ArrayList<>();
        for (JsonNode q : questions(form)) {
            String type = q.path("type").asText();
            if ("STATEMENT".equals(type)) continue;
            String legacy = switch (type) {
                case "LONG_TEXT" -> "TEXTAREA";
                case "DROPDOWN" -> "SELECT";
                case "SINGLE", "YES_NO" -> "RADIO";
                case "DATE" -> "DATE";
                case "TIME" -> "TIME";
                default -> "TEXT";
            };
            List<String> opts = new ArrayList<>();
            if (Set.of("SINGLE", "DROPDOWN", "YES_NO").contains(type)) q.path("options").forEach(o -> opts.add(o.path("label").asText()));
            out.add(new CampaignFieldDto(q.path("id").asText(), q.path("label").asText(), legacy, opts, q.path("required").asBoolean(false)));
        }
        return out;
    }

    /** Toutes les questions, dans l'ordre. */
    public static List<JsonNode> questions(JsonNode form) {
        List<JsonNode> out = new ArrayList<>();
        if (form == null) return out;
        for (JsonNode s : form.path("sections")) for (JsonNode q : s.path("questions")) out.add(q);
        return out;
    }

    // ───────────────────────────── Évaluation ─────────────────────────────

    public record Evaluation(Set<String> visible, Map<String, String> errors, Map<String, String> answers,
                             int score, int maxScore, Integer scorePct, String suggestedStatus, String suggestedLabel, int answered, int answerable) {
        public boolean valid() { return errors.isEmpty(); }
    }

    /** Questions affichées, contrôles, réponses nettoyées (questions masquées retirées), score et issue suggérée. */
    public static Evaluation evaluate(JsonNode form, Map<String, String> rawAnswers) {
        Map<String, String> in = rawAnswers == null ? Map.of() : rawAnswers;
        Map<String, String> clean = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> visible = new LinkedHashSet<>();
        int score = 0, max = 0, answered = 0, answerable = 0;
        String optionOutcome = null, optionOutcomeLabel = null;
        for (JsonNode s : form.path("sections")) {
            if (!matches(s.get("visibleIf"), clean)) continue;
            for (JsonNode q : s.path("questions")) {
                if (!matches(q.get("visibleIf"), clean)) continue;
                String id = q.path("id").asText(), type = q.path("type").asText();
                visible.add(id);
                if ("STATEMENT".equals(type)) continue;
                answerable++;
                String v = in.get(id);
                v = v == null ? null : v.trim();
                boolean empty = isEmpty(v, type);
                if (empty) {
                    if (q.path("required").asBoolean(false)) errors.put(id, "« " + q.path("label").asText() + " » est obligatoire.");
                } else {
                    String err = validate(q, v);
                    if (err != null) errors.put(id, err);
                    else { clean.put(id, v); answered++; }
                }
                // Score : choix (points de l'option) ou valeur pondérée (échelle, note, NPS, nombre).
                int[] sm = scoreOf(q, empty ? null : clean.get(id));
                score += sm[0];
                max += sm[1];
                if (!empty && clean.containsKey(id) && optionOutcome == null) {
                    for (JsonNode o : selectedOptions(q, clean.get(id))) {
                        if (o.has("outcome")) { optionOutcome = o.get("outcome").asText(); optionOutcomeLabel = o.path("label").asText(); break; }
                    }
                }
            }
        }
        Integer pct = max > 0 ? Math.max(0, Math.min(100, Math.round(score * 100f / max))) : null;
        String status = null, label = null;
        for (JsonNode o : form.path("outcomes")) {
            if (matches(o.get("when"), clean)) { status = o.path("status").asText(); label = o.path("label").asText(null); break; }
        }
        if (status == null && optionOutcome != null) { status = optionOutcome; label = "Réponse « " + optionOutcomeLabel + " »"; }
        int threshold = form.path("settings").path("qualifiedThreshold").asInt(0);
        if (status == null && pct != null && threshold > 0 && pct >= threshold) { status = "GREEN"; label = "Lead qualifié (" + pct + " %)"; }
        return new Evaluation(visible, errors, clean, score, max, pct, status, label, answered, answerable);
    }

    static boolean isEmpty(String v, String type) {
        if (v == null || v.isBlank()) return true;
        if ("CONSENT".equals(type)) return !"true".equalsIgnoreCase(v);
        if (v.equals("[]") || v.equals("{}")) return true;
        return false;
    }

    /** null si la réponse est conforme, sinon le message à afficher. */
    static String validate(JsonNode q, String v) {
        String type = q.path("type").asText(), label = q.path("label").asText();
        JsonNode val = q.path("validation");
        String custom = val.path("message").asText(null);
        switch (type) {
            case "SINGLE", "DROPDOWN", "YES_NO" -> {
                if (findOption(q, v) == null && !(q.path("allowOther").asBoolean(false) && v.startsWith("Autre:"))) return "Choix non proposé pour « " + label + " ».";
            }
            case "MULTIPLE" -> {
                List<String> list = list(v);
                if (list == null) return "Réponse illisible pour « " + label + " ».";
                for (String x : list) if (findOption(q, x) == null && !(q.path("allowOther").asBoolean(false) && x.startsWith("Autre:"))) return "Choix non proposé pour « " + label + " » : " + x + ".";
                int minS = val.path("minSelect").asInt(0), maxS = val.path("maxSelect").asInt(0);
                if (minS > 0 && list.size() < minS) return msg(custom, "Choisissez au moins " + minS + " réponse(s) pour « " + label + " ».");
                if (maxS > 0 && list.size() > maxS) return msg(custom, "Au plus " + maxS + " réponse(s) pour « " + label + " ».");
            }
            case "RANKING" -> {
                List<String> list = list(v);
                if (list == null || list.size() != q.path("options").size() || new HashSet<>(list).size() != list.size()) return "Classez tous les choix de « " + label + " ».";
                for (String x : list) if (findOption(q, x) == null) return "Choix non proposé pour « " + label + " ».";
            }
            case "MATRIX" -> {
                Map<String, String> m = map(v);
                if (m == null) return "Réponse illisible pour « " + label + " ».";
                for (JsonNode r : q.path("rows")) {
                    String c = m.get(r.path("label").asText());
                    if (c == null) { if (q.path("required").asBoolean(false)) return "Répondez à chaque ligne de « " + label + " »."; continue; }
                    if (findOption(q, c) == null) return "Choix non proposé pour « " + label + " ».";
                }
            }
            case "SCALE", "RATING", "NPS" -> {
                Integer n = integer(v);
                int min = "NPS".equals(type) ? 0 : q.path("scale").path("min").asInt(1), max = "NPS".equals(type) ? 10 : q.path("scale").path("max").asInt(5);
                if (n == null || n < min || n > max) return "« " + label + " » : une valeur de " + min + " à " + max + ".";
            }
            case "NUMBER", "AMOUNT" -> {
                BigDecimal n = decimal(v);
                if (n == null) return msg(custom, "« " + label + " » : un nombre est attendu.");
                if (val.has("min") && n.compareTo(val.get("min").decimalValue()) < 0) return msg(custom, "« " + label + " » : au moins " + val.get("min").asText() + ".");
                if (val.has("max") && n.compareTo(val.get("max").decimalValue()) > 0) return msg(custom, "« " + label + " » : au plus " + val.get("max").asText() + ".");
            }
            case "EMAIL" -> { if (!EMAIL.matcher(v).matches()) return msg(custom, "« " + label + " » : adresse e-mail invalide."); }
            case "PHONE" -> {
                String digits = v.replaceAll("[\\s.\\-()]", "");
                if (!digits.matches("\\+?\\d{8,15}")) return msg(custom, "« " + label + " » : numéro de téléphone invalide (8 à 15 chiffres).");
            }
            case "DATE" -> {
                LocalDate d;
                try { d = LocalDate.parse(v); } catch (RuntimeException e) { return "« " + label + " » : date invalide."; }
                LocalDate min = relativeDate(val.path("minDate").asText(null)), max = relativeDate(val.path("maxDate").asText(null));
                if (min != null && d.isBefore(min)) return msg(custom, "« " + label + " » : pas avant le " + min + ".");
                if (max != null && d.isAfter(max)) return msg(custom, "« " + label + " » : pas après le " + max + ".");
            }
            case "TIME" -> { try { LocalTime.parse(v); } catch (RuntimeException e) { return "« " + label + " » : heure invalide."; } }
            case "CONSENT" -> { if (!"true".equalsIgnoreCase(v)) return "« " + label + " » doit être accepté."; }
            default -> { /* texte libre */ }
        }
        if (Set.of("SHORT_TEXT", "LONG_TEXT").contains(type)) {
            int minL = val.path("minLength").asInt(0), maxL = val.path("maxLength").asInt(0);
            if (minL > 0 && v.length() < minL) return msg(custom, "« " + label + " » : au moins " + minL + " caractères.");
            if (maxL > 0 && v.length() > maxL) return msg(custom, "« " + label + " » : au plus " + maxL + " caractères.");
            String p = val.path("pattern").asText("");
            if (!p.isBlank() && !Pattern.compile(p).matcher(v).matches()) return msg(custom, "« " + label + " » : format non respecté.");
        }
        if (v.length() > 4000) return "« " + label + " » : réponse trop longue.";
        return null;
    }

    private static String msg(String custom, String fallback) { return custom != null && !custom.isBlank() ? custom : fallback; }

    static LocalDate relativeDate(String s) {
        if (s == null || s.isBlank()) return null;
        if ("today".equalsIgnoreCase(s)) return LocalDate.now();
        try { return LocalDate.parse(s); } catch (RuntimeException e) { return null; }
    }

    /** { points obtenus, points possibles } d'une question. */
    static int[] scoreOf(JsonNode q, String v) {
        String type = q.path("type").asText();
        if (CHOICE_TYPES.contains(type) && !"RANKING".equals(type) || "YES_NO".equals(type)) {
            int best = 0, sumPos = 0;
            for (JsonNode o : q.path("options")) { int s = o.path("score").asInt(0); best = Math.max(best, s); if (s > 0) sumPos += s; }
            int got = 0;
            if (v != null) for (JsonNode o : selectedOptions(q, v)) got += o.path("score").asInt(0);
            return new int[]{got, "MULTIPLE".equals(type) ? sumPos : best};
        }
        double w = q.path("weight").asDouble(0);
        if (w == 0) return new int[]{0, 0};
        int max = "NPS".equals(type) ? 10 : q.path("scale").path("max").asInt(q.path("validation").path("max").asInt(0));
        BigDecimal n = v == null ? null : decimal(v);
        int got = n == null ? 0 : (int) Math.round(Math.min(n.doubleValue(), max > 0 ? max : n.doubleValue()) * w);
        return new int[]{got, (int) Math.round(max * w)};
    }

    static List<JsonNode> selectedOptions(JsonNode q, String v) {
        List<JsonNode> out = new ArrayList<>();
        if (v == null) return out;
        String type = q.path("type").asText();
        List<String> values = "MULTIPLE".equals(type) ? list(v) : List.of(v);
        if (values == null) return out;
        for (String x : values) { JsonNode o = findOption(q, x); if (o != null) out.add(o); }
        return out;
    }

    static JsonNode findOption(JsonNode q, String v) {
        if (v == null) return null;
        for (JsonNode o : q.path("options")) {
            if (o.path("label").asText().equalsIgnoreCase(v.trim()) || o.path("id").asText().equals(v.trim())) return o;
        }
        return null;
    }

    // ───────────────────────────── Conditions ─────────────────────────────

    /** Condition { logic: all|any, rules: [{ q, op, value }] } — absente = toujours vraie. */
    public static boolean matches(JsonNode cond, Map<String, String> answers) {
        if (cond == null || cond.isNull() || !cond.has("rules") || cond.get("rules").isEmpty()) return true;
        boolean any = "any".equalsIgnoreCase(cond.path("logic").asText("all"));
        for (JsonNode r : cond.get("rules")) {
            boolean ok = rule(r, answers.get(r.path("q").asText()));
            if (any && ok) return true;
            if (!any && !ok) return false;
        }
        return !any;
    }

    static boolean rule(JsonNode r, String answer) {
        String op = r.path("op").asText("eq");
        boolean empty = answer == null || answer.isBlank() || answer.equals("[]") || answer.equals("{}");
        if ("empty".equals(op)) return empty;
        if ("notempty".equals(op)) return !empty;
        if (empty) return "neq".equals(op) || "nin".equals(op) || "ncontains".equals(op);
        List<String> values = new ArrayList<>();
        JsonNode val = r.get("value");
        if (val != null && val.isArray()) val.forEach(x -> values.add(x.asText()));
        else if (val != null && !val.isNull()) values.add(val.asText());
        List<String> got = answer.startsWith("[") ? Optional.ofNullable(list(answer)).orElse(List.of(answer)) : List.of(answer);
        switch (op) {
            case "eq": return !values.isEmpty() && got.size() == 1 && got.get(0).equalsIgnoreCase(values.get(0));
            case "neq": return values.isEmpty() || got.size() != 1 || !got.get(0).equalsIgnoreCase(values.get(0));
            case "in": return got.stream().anyMatch(g -> values.stream().anyMatch(g::equalsIgnoreCase));
            case "nin": return got.stream().noneMatch(g -> values.stream().anyMatch(g::equalsIgnoreCase));
            case "contains": return !values.isEmpty() && (got.stream().anyMatch(g -> g.equalsIgnoreCase(values.get(0)))
                    || answer.toLowerCase(Locale.ROOT).contains(values.get(0).toLowerCase(Locale.ROOT)));
            case "ncontains": return values.isEmpty() || !answer.toLowerCase(Locale.ROOT).contains(values.get(0).toLowerCase(Locale.ROOT));
            default: {
                BigDecimal a = decimal(got.get(0)), b = values.isEmpty() ? null : decimal(values.get(0));
                if (a == null || b == null) {
                    // Dates ISO : comparaison de texte.
                    if (values.isEmpty()) return false;
                    int c = got.get(0).compareTo(values.get(0));
                    return switch (op) { case "gt" -> c > 0; case "gte" -> c >= 0; case "lt" -> c < 0; default -> c <= 0; };
                }
                int c = a.compareTo(b);
                return switch (op) { case "gt" -> c > 0; case "gte" -> c >= 0; case "lt" -> c < 0; default -> c <= 0; };
            }
        }
    }

    // ───────────────────────────── Lecture des valeurs ─────────────────────────────

    static List<String> list(String v) {
        try {
            JsonNode n = JSON.readTree(v);
            if (!n.isArray()) return null;
            List<String> out = new ArrayList<>();
            n.forEach(x -> out.add(x.asText()));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    static Map<String, String> map(String v) {
        try {
            JsonNode n = JSON.readTree(v);
            if (!n.isObject()) return null;
            Map<String, String> out = new LinkedHashMap<>();
            n.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    static Integer integer(String v) {
        try { return Integer.parseInt(v.trim()); } catch (RuntimeException e) { return null; }
    }

    static BigDecimal decimal(String v) {
        if (v == null) return null;
        String s = v.replace(' ', ' ').replace(" ", "").replace(",", ".");
        try { return new BigDecimal(s); } catch (RuntimeException e) { return null; }
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }
}
