package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.BankBranch;
import com.ecobank.rccportal.model.CardProduct;
import com.ecobank.rccportal.repository.BankBranchRepository;
import com.ecobank.rccportal.repository.CardProductRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Suggestions de réponse pour les champs [EN_CROCHETS] des masques de mail — valables pour
 * TOUS les masques, y compris ceux que les agents créent eux-mêmes : la suggestion dépend du
 * nom du champ, pas d'un paramétrage du masque.
 * <ul>
 *   <li>coordonnées / pièces à demander : listes à cocher (plusieurs choix, mises en liste dans le mail) ;</li>
 *   <li>agence, type de carte, produit : listes réelles du portail ;</li>
 *   <li>délai, canal, devise, civilité, statut, motif : réponses usuelles ;</li>
 *   <li>date, conseiller : pré-remplis ;</li>
 *   <li>autres champs : réponses les plus utilisées par les agents (jamais les données personnelles
 *       du client : nom, compte, carte, téléphone, e-mail, montant, référence…).</li>
 * </ul>
 * À la création d'un masque, les champs adaptés au type de mail sont proposés (demande de
 * coordonnées, carte, réclamation, virement, pièces, rendez-vous).
 */
@Service
public class MailFieldSuggestionService {

    /** kind : CHOICE (une réponse), MULTI (plusieurs, en liste), PREFILL (valeur proposée d'office), FREE (saisie libre). */
    public record FieldSuggestion(String key, String label, String kind, List<String> options, String prefill, boolean personal, String hint) {}

    public record SuggestedField(String key, String label, String reason) {}

    private static final DateTimeFormatter FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    static final List<String> COORDINATES = List.of("Nom et prénoms", "Numéro de compte", "Numéro de téléphone", "Adresse e-mail",
            "Adresse de domicile", "Date et lieu de naissance", "Numéro de la pièce d'identité", "Profession / employeur");
    static final List<String> DOCUMENTS = List.of("Copie de la CNI ou du passeport en cours de validité", "Justificatif de domicile de moins de 3 mois",
            "3 derniers bulletins de salaire", "Relevé d'identité bancaire (RIB)", "Photo d'identité récente", "Formulaire de demande signé",
            "Relevés bancaires des 3 derniers mois", "Attestation de travail");
    static final List<String> DELAYS = List.of("24 heures", "48 heures", "72 heures", "5 jours ouvrés", "10 jours ouvrés", "15 jours ouvrés");
    static final List<String> CHANNELS = List.of("E-mail", "Téléphone", "En agence", "WhatsApp", "Ecobank Mobile", "Internet Banking");
    static final List<String> CURRENCIES = List.of("FCFA (XOF)", "EUR", "USD", "GHS", "NGN");
    static final List<String> CIVILITIES = List.of("Monsieur", "Madame", "Mademoiselle");
    static final List<String> STATUSES = List.of("En cours de traitement", "Traité", "En attente de pièces justificatives", "Transmis au service concerné", "Rejeté");
    static final List<String> REASONS = List.of("Demande d'information", "Réclamation", "Mise à jour des coordonnées", "Opposition", "Activation",
            "Déblocage", "Contestation de transaction", "Demande de document");
    static final List<String> PRODUCTS = List.of("Compte courant", "Compte épargne", "Carte bancaire", "Ecobank Mobile", "Internet Banking",
            "Rapidtransfer", "Prêt personnel", "Découvert");
    static final List<String> CARD_FALLBACK = List.of("Classic", "Gold", "Platinum", "Infinite", "Prépayée");

    /** Mots qui désignent une donnée personnelle du client : jamais apprise ni proposée à d'autres agents. */
    private static final List<String> PERSONAL = List.of("CLIENT", "COMPTE", "NUMERO", "PIN", "EMAIL", "MAIL", "CONTACT", "TEL",
            "MONTANT", "REFERENCE", "REF", "CLE", "ADRESSE", "IBAN", "RIB", "NAISSANCE", "CNI", "PASSEPORT", "ID", "SOLDE", "MOT_DE_PASSE", "CODE", "NOM");

    private final JdbcTemplate jdbc;
    private final BankBranchRepository branches;
    private final CardProductRepository cards;
    private final UserRepository users;
    private final MailTemplateService templates;

    public MailFieldSuggestionService(JdbcTemplate jdbc, BankBranchRepository branches, CardProductRepository cards, UserRepository users,
                                      MailTemplateService templates) {
        this.jdbc = jdbc;
        this.branches = branches;
        this.cards = cards;
        this.users = users;
        this.templates = templates;
    }

    static String norm(String key) {
        return Normalizer.normalize(key == null ? "" : key, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
    }

    private static boolean has(String k, String... words) {
        for (String w : words) if (k.equals(w) || k.startsWith(w + "_") || k.endsWith("_" + w) || k.contains("_" + w + "_")) return true;
        return false;
    }

    /** Champ contenant une donnée personnelle du client (sauf le nom du conseiller / de l'agent). */
    static boolean isPersonal(String key) {
        String k = norm(key);
        if (has(k, "CONSEILLER", "AGENT", "SIGNATURE", "AGENCE", "SERVICE") && !has(k, "CLIENT")) return false;
        String padded = "_" + k + "_";
        for (String w : PERSONAL) if (padded.contains("_" + w + "_")) return true; // mot entier : TYPE_DE_CARTE n'est pas personnel
        return false;
    }

    public Map<String, FieldSuggestion> suggestionsForTemplate(AuthenticatedUser requester, Integer templateId) {
        List<String> keys = templates.listPlaceholders(templateId, requester); // contrôle de visibilité inclus
        Map<String, FieldSuggestion> out = new LinkedHashMap<>();
        Map<String, List<String>> learned = learned(keys);
        String me = requester == null ? null : users.findFirstByUsernameIgnoreCase(requester.username())
                .map(u -> u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getUsername()).orElse(requester.name());
        for (String key : keys) out.put(key, suggest(key, learned.getOrDefault(norm(key), List.of()), me));
        return out;
    }

    FieldSuggestion suggest(String key, List<String> learnedValues, String advisorName) {
        String k = norm(key);
        String label = humanize(key);
        boolean personal = isPersonal(key);
        if (has(k, "COORDONNEES", "INFORMATIONS", "INFOS", "ELEMENTS", "DONNEES") || k.contains("COORDONNEE")) {
            return new FieldSuggestion(key, label, "MULTI", COORDINATES, null, false, "Cochez les informations à demander au client.");
        }
        if (has(k, "PIECES", "PIECE", "DOCUMENTS", "DOCUMENT", "JUSTIFICATIFS", "JUSTIFICATIF", "DOSSIER")) {
            return new FieldSuggestion(key, label, "MULTI", DOCUMENTS, null, false, "Cochez les pièces à fournir.");
        }
        if (has(k, "CONSEILLER", "AGENT", "SIGNATURE", "SIGNATAIRE") && !has(k, "CLIENT")) {
            return new FieldSuggestion(key, label, "PREFILL", advisorName == null ? List.of() : List.of(advisorName), advisorName, false, "Votre nom, proposé d'office.");
        }
        if (has(k, "AGENCE", "LIEU", "BRANCH")) {
            List<String> names = new ArrayList<>();
            try {
                for (BankBranch b : branches.findByCountryCodeIgnoreCaseAndActiveTrueOrderByCityAscNameAsc("CI")) names.add(b.getName());
            } catch (RuntimeException ignored) {
                // agences non chargées
            }
            if (has(k, "LIEU")) names.add(0, "Ecobank Mobile (à distance)");
            return new FieldSuggestion(key, label, "CHOICE", merge(learnedValues, names, 80), null, false, "Choisissez l'agence.");
        }
        if (has(k, "TYPE_DE_CARTE", "TYPE_CARTE") || k.equals("CARTE") || k.equals("TYPE_DE_CARTE")) {
            List<String> names = new ArrayList<>();
            try {
                for (CardProduct c : cards.findByCountryCodeIgnoreCaseAndActiveTrueOrderBySortOrderAscNameAsc("CI")) names.add(c.getName());
            } catch (RuntimeException ignored) {
                // catalogue absent
            }
            return new FieldSuggestion(key, label, "CHOICE", merge(learnedValues, names.isEmpty() ? CARD_FALLBACK : names, 30), null, false, null);
        }
        if (has(k, "PRODUIT", "OFFRE", "SERVICE")) return new FieldSuggestion(key, label, "CHOICE", merge(learnedValues, PRODUCTS, 20), null, false, null);
        if (has(k, "DELAI", "DUREE")) return new FieldSuggestion(key, label, "CHOICE", merge(learnedValues, DELAYS, 12), null, false, null);
        if (has(k, "CANAL", "MOYEN", "CANAUX")) return new FieldSuggestion(key, label, "CHOICE", merge(learnedValues, CHANNELS, 12), null, false, null);
        if (has(k, "DEVISE", "MONNAIE")) return new FieldSuggestion(key, label, "CHOICE", CURRENCIES, "FCFA (XOF)", false, null);
        if (has(k, "CIVILITE", "TITRE")) return new FieldSuggestion(key, label, "CHOICE", CIVILITIES, null, false, null);
        if (has(k, "STATUT", "ETAT")) return new FieldSuggestion(key, label, "CHOICE", merge(learnedValues, STATUSES, 12), null, false, null);
        if (has(k, "MOTIF", "RAISON", "OBJET", "NATURE")) return new FieldSuggestion(key, label, "CHOICE", merge(learnedValues, REASONS, 15), null, false, null);
        if (has(k, "DATE") && !has(k, "NAISSANCE")) {
            LocalDate today = LocalDate.now();
            return new FieldSuggestion(key, label, "CHOICE", List.of(today.format(FR), today.plusDays(1).format(FR), today.plusDays(2).format(FR), today.minusDays(1).format(FR)),
                    today.format(FR), false, "Aujourd'hui proposé d'office.");
        }
        if (has(k, "HEURE")) return new FieldSuggestion(key, label, "CHOICE", List.of("08h00", "09h00", "10h00", "11h00", "14h00", "15h00", "16h00"), null, false, null);
        if (personal) return new FieldSuggestion(key, label, "FREE", List.of(), null, true, hint(k));
        return new FieldSuggestion(key, label, learnedValues.isEmpty() ? "FREE" : "CHOICE", learnedValues, null, false,
                learnedValues.isEmpty() ? null : "Réponses les plus utilisées par les agents.");
    }

    private static String hint(String k) {
        if (has(k, "COMPTE")) return "Numéro de compte du client.";
        if (has(k, "EMAIL", "MAIL")) return "Adresse e-mail du client.";
        if (has(k, "CONTACT", "TEL", "TELEPHONE")) return "Numéro de téléphone du client.";
        if (has(k, "MONTANT")) return "Montant en FCFA.";
        return "Donnée du client — à saisir.";
    }

    private static List<String> merge(List<String> first, List<String> then, int max) {
        LinkedHashSet<String> set = new LinkedHashSet<>(first);
        set.addAll(then);
        List<String> out = new ArrayList<>(set);
        return out.size() > max ? out.subList(0, max) : out;
    }

    /** Accents restitués sur les noms de champs écrits sans accent (COORDONNEES_DEMANDEES → « Coordonnées demandées »). */
    private static final Map<String, String> ACCENTS = Map.ofEntries(Map.entry("coordonnees", "coordonnées"), Map.entry("demandees", "demandées"), Map.entry("demandes", "demandés"), Map.entry("pieces", "pièces"), Map.entry("piece", "pièce"), Map.entry("a", "à"), Map.entry("numero", "numéro"), Map.entry("delai", "délai"), Map.entry("telephone", "téléphone"), Map.entry("civilite", "civilité"), Map.entry("reference", "référence"), Map.entry("etat", "état"), Map.entry("cle", "clé"), Map.entry("details", "détails"), Map.entry("detail", "détail"), Map.entry("identite", "identité"), Map.entry("prenoms", "prénoms"), Map.entry("operation", "opération"), Map.entry("precedent", "précédent"), Map.entry("donnees", "données"), Map.entry("electronique", "électronique"), Map.entry("reclamation", "réclamation"), Map.entry("echeance", "échéance"), Map.entry("beneficiaire", "bénéficiaire"), Map.entry("societe", "société"), Map.entry("eligible", "éligible"), Map.entry("debit", "débit"), Map.entry("credit", "crédit"), Map.entry("activite", "activité"));

    static String humanize(String key) {
        StringBuilder sb = new StringBuilder();
        for (String w : key.toLowerCase(Locale.FRENCH).split("_")) {
            if (w.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(ACCENTS.getOrDefault(w, w));
        }
        String lower = sb.toString();
        return lower.isEmpty() ? key : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Réponses apprises (les plus utilisées, puis les plus récentes) pour les champs non personnels. */
    private Map<String, List<String>> learned(List<String> keys) {
        Map<String, List<String>> out = new HashMap<>();
        for (String key : keys) {
            if (isPersonal(key)) continue;
            String k = norm(key);
            try {
                out.put(k, jdbc.queryForList("SELECT TOP 8 FieldValue FROM dbo.MailFieldHistory WHERE FieldKey = ? ORDER BY UseCount DESC, LastUsedAt DESC",
                        String.class, k));
            } catch (RuntimeException ignored) {
                // table absente
            }
        }
        return out;
    }

    /** Mémorise les réponses d'un mail envoyé / copié (jamais les champs personnels, ni les listes multi-choix). */
    public int remember(AuthenticatedUser requester, Integer templateId, Map<String, String> values) {
        if (values == null || values.isEmpty()) return 0;
        Set<String> keys = new HashSet<>(templates.listPlaceholders(templateId, requester));
        int stored = 0;
        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = e.getKey(), value = e.getValue() == null ? "" : e.getValue().trim();
            if (!keys.contains(key) || value.isEmpty() || value.length() > 300 || value.contains("\n") || isPersonal(key)) continue;
            String k = norm(key);
            if (has(k, "COORDONNEES", "PIECES", "DOCUMENTS", "DATE", "HEURE", "CONSEILLER", "AGENT", "SIGNATURE")) continue;
            try {
                int n = jdbc.update("UPDATE dbo.MailFieldHistory SET UseCount = UseCount + 1, LastUsedAt = SYSUTCDATETIME() WHERE FieldKey = ? AND FieldValue = ?", k, value);
                if (n == 0) jdbc.update("INSERT INTO dbo.MailFieldHistory (FieldKey, FieldValue) VALUES (?, ?)", k, value);
                stored++;
            } catch (RuntimeException ignored) {
                // meilleur effort
            }
        }
        return stored;
    }

    /** Champs proposés à la création d'un masque, selon le sujet du mail (champs déjà présents exclus). */
    public List<SuggestedField> suggestFields(String subject, String body) {
        String text = Normalizer.normalize(((subject == null ? "" : subject) + " " + (body == null ? "" : body)), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        LinkedHashMap<String, String[]> out = new LinkedHashMap<>();
        java.util.function.BiConsumer<String, String> add = (key, reason) -> out.putIfAbsent(key, new String[]{humanize(key), reason});
        if (text.matches("(?s).*(coordonn|mise a jour|mettre a jour|kyc|informations? (personnelles|client)|actualis).*")) {
            add.accept("NOM_DU_CLIENT", "Demande de coordonnées");
            add.accept("NUMERO_DE_COMPTE", "Demande de coordonnées");
            add.accept("COORDONNEES_DEMANDEES", "Liste des informations à fournir (à cocher)");
            add.accept("PIECES_A_FOURNIR", "Justificatifs éventuels (à cocher)");
            add.accept("DELAI", "Délai de réponse attendu");
            add.accept("CANAL", "Moyen de transmission");
        }
        if (text.matches("(?s).*(carte|opposition|pin|visa|mastercard|gab|dab).*")) {
            add.accept("NOM_DU_CLIENT", "Mail carte");
            add.accept("TYPE_DE_CARTE", "Type de carte (liste)");
            add.accept("AGENCE", "Agence de retrait (liste)");
        }
        if (text.matches("(?s).*(reclamation|contestation|litige|transaction|debit|prelevement).*")) {
            add.accept("REFERENCE", "Référence de la réclamation");
            add.accept("MONTANT", "Montant concerné");
            add.accept("DATE", "Date de l'opération");
            add.accept("DELAI", "Délai de traitement annoncé");
            add.accept("STATUT", "État du dossier");
        }
        if (text.matches("(?s).*(virement|transfert|rapidtransfer|envoi d'argent).*")) {
            add.accept("MONTANT", "Montant du virement");
            add.accept("DEVISE", "Devise");
            add.accept("REFERENCE", "Référence de l'opération");
            add.accept("DATE", "Date de l'opération");
        }
        if (text.matches("(?s).*(document|piece|dossier|pret|credit|ouverture de compte|justificatif).*")) {
            add.accept("PIECES_A_FOURNIR", "Pièces à fournir (à cocher)");
            add.accept("AGENCE", "Agence où déposer le dossier");
            add.accept("DELAI", "Délai");
        }
        if (text.matches("(?s).*(rendez-vous|rendez vous|rdv|passage en agence|se presenter).*")) {
            add.accept("AGENCE", "Agence du rendez-vous");
            add.accept("DATE", "Date du rendez-vous");
            add.accept("HEURE", "Heure du rendez-vous");
        }
        add.accept("NOM_DU_CLIENT", "Personnalise le mail");
        add.accept("NOM_CONSEILLER", "Signature (pré-remplie)");
        Set<String> present = new HashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[([A-Za-zÀ-ÿ_]+)]").matcher(subject + " " + body);
        while (m.find()) present.add(norm(m.group(1)));
        List<SuggestedField> list = new ArrayList<>();
        out.forEach((k, v) -> { if (!present.contains(norm(k))) list.add(new SuggestedField(k, v[0], v[1])); });
        return list;
    }
}
