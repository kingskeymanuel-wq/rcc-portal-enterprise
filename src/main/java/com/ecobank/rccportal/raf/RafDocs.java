package com.ecobank.rccportal.raf;

import java.util.List;
import java.util.Set;

/**
 * Instantané immuable des données du portail sur lesquelles RAF s'appuie — les agents RAF ne
 * manipulent jamais d'entités JPA (pas de chargement paresseux hors transaction, tests sans
 * base de données : un {@link Snapshot} se construit directement).
 */
public final class RafDocs {

    private RafDocs() {
    }

    public record StepDoc(int number, String content) {
    }

    public record ProcedureDoc(int id, String title, String countryCode, String level, String responsibleTeam,
                               String slaDelay, List<StepDoc> steps) {
    }

    public record SlaDoc(int id, String motif, String category, String level, Integer slaHours, String slaLabel,
                         String destinationService, String priority, boolean autoEscalation, String notes) {
    }

    public record TermDoc(int id, String term, String definition, String category) {
    }

    /** managerName volontairement absent : donnée personnelle, jamais restituée par RAF. */
    public record BranchDoc(long id, String countryCode, String city, String name, String address,
                            Double latitude, Double longitude, String phone, String openingHours, String type) {
    }

    public record CountryDoc(String code, String label, String flag, Set<String> aliases) {
    }

    /** Question de la banque d'évaluation dont la bonne réponse a été vérifiée par la QA. */
    public record VerifiedQaDoc(int id, String question, String answer, String explanation, String category, String tags) {
    }

    public record ArticleDoc(int id, String title, String tags, String plainText, String countryCode) {
    }

    public record CourseDoc(int id, String title, String category, String description) {
    }

    public record MailTemplateDoc(int id, String subject, String body, String categoryLabel) {
    }

    public record Snapshot(List<ProcedureDoc> procedures, List<SlaDoc> slaRules, List<TermDoc> terms,
                           List<BranchDoc> branches, List<CountryDoc> countries, List<VerifiedQaDoc> verifiedQa,
                           List<ArticleDoc> articles, List<CourseDoc> courses, List<MailTemplateDoc> mailTemplates) {

        public static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }
}
