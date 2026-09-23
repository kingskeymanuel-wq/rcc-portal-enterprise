package com.ecobank.rccportal.raf;

import com.ecobank.rccportal.model.Procedure;
import com.ecobank.rccportal.model.ProcedureStep;
import com.ecobank.rccportal.raf.RafDocs.*;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.service.QuizQuestionService;
import com.ecobank.rccportal.util.SearchText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Données du portail vues par RAF — instantané reconstruit au plus toutes les 5 minutes (une
 * modification faite dans l'Administration est prise en compte sans redémarrage). Chaque
 * source est chargée indépendamment : une table absente sur une installation ne prive pas RAF
 * des autres.
 */
@Service
public class RafCatalog {

    private static final Logger log = LoggerFactory.getLogger(RafCatalog.class);
    private static final long TTL_MS = 5 * 60 * 1000L;

    /** Noms usuels des pays (fr/en/pt) en plus des libellés de la table KnowledgeCountries. */
    private static final Map<String, List<String>> COUNTRY_ALIASES = Map.ofEntries(
            Map.entry("CI", List.of("cote d ivoire", "cote divoire", "ivory coast", "rci", "abidjan", "costa do marfim")),
            Map.entry("SN", List.of("senegal", "dakar")),
            Map.entry("ML", List.of("mali", "bamako")),
            Map.entry("BF", List.of("burkina", "burkina faso", "ouagadougou")),
            Map.entry("BJ", List.of("benin", "cotonou")),
            Map.entry("TG", List.of("togo", "lome")),
            Map.entry("NE", List.of("niger", "niamey")),
            Map.entry("GW", List.of("guinee bissau", "guinea bissau", "bissau")),
            Map.entry("GN", List.of("guinee", "guinea", "conakry")),
            Map.entry("CM", List.of("cameroun", "cameroon", "douala", "yaounde")),
            Map.entry("GH", List.of("ghana", "accra")),
            Map.entry("NG", List.of("nigeria", "lagos")));

    private final ProcedureRepository procedureRepository;
    private final ProcedureStepRepository procedureStepRepository;
    private final SlaRuleRepository slaRuleRepository;
    private final WordTermRepository wordTermRepository;
    private final BankBranchRepository bankBranchRepository;
    private final KnowledgeCountryRepository countryRepository;
    private final KnowledgeArticleRepository articleRepository;
    private final CourseRepository courseRepository;
    private final MailTemplateRepository mailTemplateRepository;
    private final QuizQuestionService quizQuestionService;

    private volatile RafDocs.Snapshot snapshot;
    private volatile long builtAt;

    /** Transaction en lecture pour construire l'instantané (relations chargées paresseusement). */
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransactionTemplate(org.springframework.transaction.support.TransactionTemplate transactionTemplate) {
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(
                transactionTemplate.getTransactionManager());
        this.transactionTemplate.setReadOnly(true);
    }

    public RafCatalog(ProcedureRepository procedureRepository, ProcedureStepRepository procedureStepRepository,
                      SlaRuleRepository slaRuleRepository, WordTermRepository wordTermRepository,
                      BankBranchRepository bankBranchRepository, KnowledgeCountryRepository countryRepository,
                      KnowledgeArticleRepository articleRepository, CourseRepository courseRepository,
                      MailTemplateRepository mailTemplateRepository, QuizQuestionService quizQuestionService) {
        this.procedureRepository = procedureRepository;
        this.procedureStepRepository = procedureStepRepository;
        this.slaRuleRepository = slaRuleRepository;
        this.wordTermRepository = wordTermRepository;
        this.bankBranchRepository = bankBranchRepository;
        this.countryRepository = countryRepository;
        this.articleRepository = articleRepository;
        this.courseRepository = courseRepository;
        this.mailTemplateRepository = mailTemplateRepository;
        this.quizQuestionService = quizQuestionService;
    }

    /** Catalogue figé — tests des agents sans base de données. */
    public static RafCatalog fixed(RafDocs.Snapshot snapshot) {
        RafCatalog catalog = new RafCatalog(null, null, null, null, null, null, null, null, null, null);
        catalog.snapshot = snapshot;
        catalog.builtAt = Long.MAX_VALUE / 2;
        return catalog;
    }

    public RafDocs.Snapshot snapshot() {
        RafDocs.Snapshot current = snapshot;
        if (current != null && System.currentTimeMillis() - builtAt < TTL_MS) return current;
        synchronized (this) {
            if (snapshot != null && System.currentTimeMillis() - builtAt < TTL_MS) return snapshot;
            snapshot = build();
            builtAt = System.currentTimeMillis();
            return snapshot;
        }
    }

    public void invalidate() {
        builtAt = 0;
    }

    RafDocs.Snapshot build() {
        List<CountryDoc> countries = load("pays", this::countries);
        return new RafDocs.Snapshot(
                load("procédures", this::procedures),
                load("SLA", () -> slaRuleRepository.findByIsActiveTrueAndPoleIsNullOrderBySortOrderAscMotifAsc().stream()
                        .map(r -> new SlaDoc(r.getSlaRuleId(), r.getMotif(), r.getCategory(), r.getLevel(), r.getSlaHours(),
                                r.getSlaLabel(), r.getDestinationService(), r.getPriority(),
                                Boolean.TRUE.equals(r.getAutoEscalation()), r.getNotes()))
                        .toList()),
                load("glossaire", () -> wordTermRepository.findByActiveTrueOrderByTermAsc().stream()
                        .map(t -> new TermDoc(t.getTermId(), t.getTerm(), t.getDefinition(), t.getCategory()))
                        .toList()),
                load("agences", () -> bankBranchRepository.findAll().stream()
                        .filter(b -> b.isActive())
                        .map(b -> new BranchDoc(b.getBranchId(), b.getCountryCode(), b.getCity(), b.getName(), b.getAddress(),
                                b.getLatitude(), b.getLongitude(), b.getPhone(), b.getOpeningHours(), b.getBranchType()))
                        .toList()),
                countries,
                load("Q/R vérifiées", () -> quizQuestionService.activeVerifiedAnswers().stream()
                        .map(a -> new VerifiedQaDoc(a.questionId(), a.question(), a.answer(), a.explanation(), a.category(), a.tags()))
                        .toList()),
                load("articles", () -> articleRepository.findAll().stream()
                        .map(a -> new ArticleDoc(a.getArticleId(), a.getTitle(), a.getTags(), SearchText.stripHtml(a.getContentHtml()),
                                a.getCountry() != null ? a.getCountry().getCountryCode() : null))
                        .toList()),
                load("formations", () -> courseRepository.findAll().stream()
                        .map(c -> new CourseDoc(c.getCourseId(), c.getTitle(), c.getCategory(), c.getDescription()))
                        .toList()),
                load("modèles de mail", () -> mailTemplateRepository.findAll().stream()
                        .map(m -> new MailTemplateDoc(m.getTemplateId(), m.getSubject(), m.getBody(),
                                m.getCategory() != null ? m.getCategory().getLabel() : null))
                        .toList()));
    }

    private List<ProcedureDoc> procedures() {
        List<Procedure> procedures = procedureRepository.findAll();
        if (procedures.isEmpty()) return List.of();
        Map<Integer, List<ProcedureStep>> steps = procedureStepRepository.findByProcedureIn(procedures).stream()
                .collect(Collectors.groupingBy(s -> s.getProcedure().getProcedureId()));
        List<ProcedureDoc> out = new ArrayList<>();
        for (Procedure p : procedures) {
            List<StepDoc> docs = steps.getOrDefault(p.getProcedureId(), List.of()).stream()
                    .filter(s -> s.getContent() != null && !s.getContent().startsWith("En attente de rédaction par QA"))
                    .sorted(Comparator.comparing(ProcedureStep::getStepNumber))
                    .map(s -> new StepDoc(s.getStepNumber(), s.getContent()))
                    .toList();
            out.add(new ProcedureDoc(p.getProcedureId(), p.getTitle(), p.getCountryCode(), p.getLevel(),
                    p.getResponsibleTeam(), p.getSlaDelay(), docs));
        }
        return out;
    }

    private List<CountryDoc> countries() {
        List<CountryDoc> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var c : countryRepository.findAllByOrderBySortOrderAsc()) {
            String code = c.getCountryCode() == null ? null : c.getCountryCode().toUpperCase(Locale.ROOT);
            if (code == null) continue;
            seen.add(code);
            Set<String> aliases = new LinkedHashSet<>();
            aliases.add(SearchText.normalize(c.getLabel()));
            aliases.addAll(COUNTRY_ALIASES.getOrDefault(code, List.of()));
            out.add(new CountryDoc(code, c.getLabel(), c.getFlagEmoji(), aliases));
        }
        // Pays connus des agences ou des alias même absents de la table des pays de la KB.
        COUNTRY_ALIASES.forEach((code, aliases) -> {
            if (!seen.contains(code)) out.add(new CountryDoc(code, aliases.get(0), null, new LinkedHashSet<>(aliases)));
        });
        return out;
    }

    /** Chaque source dans SA transaction : une table absente ne fait pas échouer les autres. */
    private <T> List<T> load(String label, Supplier<List<T>> loader) {
        try {
            List<T> result = transactionTemplate != null ? transactionTemplate.execute(status -> loader.get()) : loader.get();
            return result != null ? result : List.of();
        } catch (Exception e) {
            log.warn("RAF : source « {} » indisponible ({}).", label, e.getMessage());
            return List.of();
        }
    }
}
