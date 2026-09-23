package com.ecobank.rccportal.config;

import com.ecobank.rccportal.model.*;
import com.ecobank.rccportal.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Import de la Base de connaissance "produits & services" par filiale (à
 * partir du document fourni par QA, un dossier par filiale : ECI, EBF, EBJ...)
 * + synthèse thématique en parcours interactifs (Procédures) — voir demande :
 * "adapte les thématiques aux parcours interactif... ne colle pas le fichier
 * dans l'onglet procédure mais plutôt la synthèse... pour en faire un vrai
 * parcours interactif de A à Z".
 *
 * Quatre sources de données externes (jamais codées en dur ici, éditables sans
 * recompiler) :
 * - data/knowledge-countries.json  → les 20 filiales (code ISO, devise, zone monétaire)
 * - data/knowledge-categories.json → les 12 thèmes réels de la Base de connaissance
 * - data/knowledge-articles.json   → articles de connaissance réels, filiale par filiale
 *   (Comptes, Cartes, Prêts, Produits digitaux...), synthétisés depuis les vrais
 *   documents fournis par QA — 18 des 20 filiales couvertes (CF et ST n'avaient
 *   aucun document exploitable dans le lot fourni : fichier chiffré ou absent —
 *   aucun contenu n'a été inventé pour elles, seule leur fiche pays existe)
 * - data/process-synthesis.json → parcours interactifs ADDITIFS (nouvelles fiches,
 *   ne touchent jamais aux 121 fiches déjà réelles importées par ProcedureRealContentBootstrap) :
 *   guides d'ouverture de compte branchants (par type de compte) pour 9 filiales,
 *   plus un guide d'orientation prêt pour ECI — construits à partir du même contenu
 *   réel que les articles ci-dessus, jamais collés tels quels
 *
 * Idempotent : un article n'est créé que s'il n'existe pas déjà (même titre) ;
 * un parcours synthétisé n'est créé que si aucune fiche de ce titre n'existe
 * encore — jamais de écrasement de contenu déjà là, QA garde toujours la main.
 */
@Slf4j
@Component
@Order(22) // après ProcedureRealContentBootstrap (Order 21)
public class KnowledgeBaseImportBootstrap implements CommandLineRunner {

    private record CountrySeed(String code, String label, String flagEmoji, String currency, String zone, Integer sortOrder) {}
    private record CategorySeed(String code, String title, String icon, Integer sortOrder) {}
    private record ArticleSeed(String countryCode, String categoryCode, String title, String tags, String contentHtml) {}
    private record ProcessOption(String label, String next, String outcome) {}
    private record ProcessNode(String id, String text, List<ProcessOption> options) {}
    private record ProcessSeed(String zoneCode, String countryCode, String title, List<ProcessNode> nodes) {}
    private record SlaUpdate(String slaDelay, String level, String responsibleTeam) {}
    private record NewProcedureSeed(String zoneCode, String countryCode, String title,
                                     String slaDelay, String level, String responsibleTeam, List<String> steps) {}

    private final KnowledgeCountryRepository countryRepository;
    private final KnowledgeCategoryRepository categoryRepository;
    private final KnowledgeArticleRepository articleRepository;
    private final ProcedureZoneRepository zoneRepository;
    private final ProcedureRepository procedureRepository;
    private final ProcedureStepRepository stepRepository;
    private final ProcedureWorkflowNodeRepository nodeRepository;
    private final ProcedureWorkflowOptionRepository optionRepository;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseImportBootstrap(KnowledgeCountryRepository countryRepository,
                                         KnowledgeCategoryRepository categoryRepository,
                                         KnowledgeArticleRepository articleRepository,
                                         ProcedureZoneRepository zoneRepository,
                                         ProcedureRepository procedureRepository,
                                         ProcedureStepRepository stepRepository,
                                         ProcedureWorkflowNodeRepository nodeRepository,
                                         ProcedureWorkflowOptionRepository optionRepository,
                                         ObjectMapper objectMapper) {
        this.countryRepository = countryRepository;
        this.categoryRepository = categoryRepository;
        this.articleRepository = articleRepository;
        this.zoneRepository = zoneRepository;
        this.procedureRepository = procedureRepository;
        this.stepRepository = stepRepository;
        this.nodeRepository = nodeRepository;
        this.optionRepository = optionRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        try {
            int countriesCreated = seedCountries();
            int categoriesCreated = seedCategories();
            int articlesCreated = seedArticles();
            int processesCreated = seedProcessSynthesis();
            int slaUpdated = updateProcedureSlaFields();
            int newProceduresCreated = seedNewReferentialProcedures();

            if (countriesCreated + categoriesCreated + articlesCreated + processesCreated + slaUpdated + newProceduresCreated > 0) {
                log.warn("⚠ [KB IMPORT] {} filiale(s), {} catégorie(s), {} article(s), {} parcours synthétisé(s), {} nouvelle(s) fiche(s) du référentiel créées, {} fiche(s) complétée(s) avec SLA/niveau/équipe.",
                        countriesCreated, categoriesCreated, articlesCreated, processesCreated, newProceduresCreated, slaUpdated);
            }
        } catch (Exception e) {
            // Une table nécessaire (KnowledgeCountries/Categories/Articles ou Procedures) n'existe pas
            // encore (migration pas exécutée) — pas bloquant pour le démarrage, réessaiera au prochain.
            log.warn("⚠ [KB IMPORT] Import ignoré pour l'instant (table manquante ?) : {}", e.getMessage());
        }
    }

    // ===================== FILIALES =====================

    private int seedCountries() throws Exception {
        List<CountrySeed> seeds = load("data/knowledge-countries.json", CountrySeed.class);
        int created = 0;
        for (CountrySeed s : seeds) {
            if (countryRepository.existsById(s.code())) continue;
            countryRepository.save(KnowledgeCountry.builder()
                    .countryCode(s.code()).label(s.label()).flagEmoji(s.flagEmoji())
                    .currency(s.currency()).zone(s.zone()).sortOrder(s.sortOrder() == null ? 0 : s.sortOrder())
                    .build());
            created++;
        }
        return created;
    }

    // ===================== CATÉGORIES =====================

    private int seedCategories() throws Exception {
        List<CategorySeed> seeds = load("data/knowledge-categories.json", CategorySeed.class);
        int created = 0;
        for (CategorySeed s : seeds) {
            if (categoryRepository.findByCodeIgnoreCase(s.code()).isPresent()) continue;
            categoryRepository.save(KnowledgeCategory.builder()
                    .code(s.code()).title(s.title()).icon(s.icon()).sortOrder(s.sortOrder() == null ? 0 : s.sortOrder())
                    .build());
            created++;
        }
        return created;
    }

    // ===================== ARTICLES (ECI, cette première passe) =====================

    private int seedArticles() throws Exception {
        ClassPathResource resource = new ClassPathResource("data/knowledge-articles.json");
        if (!resource.exists()) return 0;
        List<ArticleSeed> seeds = load("data/knowledge-articles.json", ArticleSeed.class);
        int created = 0;
        for (ArticleSeed s : seeds) {
            boolean alreadyExists = !articleRepository.findByTitleContainingIgnoreCaseOrTagsContainingIgnoreCase(s.title(), "___no_match___")
                    .isEmpty();
            if (alreadyExists) continue;

            KnowledgeCategory category = categoryRepository.findByCodeIgnoreCase(s.categoryCode()).orElse(null);
            if (category == null) continue;
            KnowledgeCountry country = s.countryCode() != null ? countryRepository.findById(s.countryCode()).orElse(null) : null;

            articleRepository.save(KnowledgeArticle.builder()
                    .category(category).country(country).title(s.title()).contentHtml(s.contentHtml())
                    .tags(s.tags()).sortOrder(0).build());
            created++;
        }
        return created;
    }

    // ===================== SYNTHÈSE EN PARCOURS INTERACTIFS (additif) =====================

    private int seedProcessSynthesis() throws Exception {
        ClassPathResource resource = new ClassPathResource("data/process-synthesis.json");
        if (!resource.exists()) return 0;
        List<ProcessSeed> seeds = load("data/process-synthesis.json", ProcessSeed.class);
        int created = 0;
        int backfilled = 0;
        for (ProcessSeed s : seeds) {
            Procedure existing = procedureRepository.findAllByOrderByTitleAsc().stream()
                    .filter(p -> p.getTitle().trim().equalsIgnoreCase(s.title()))
                    .findFirst().orElse(null);

            if (existing != null) {
                // La fiche existe déjà (créée lors d'un démarrage précédent, avant que ce
                // bootstrap ne sache aussi remplir ProcedureSteps — la table lue par le
                // visualiseur "Voir"). On comble uniquement les étapes manquantes avec le
                // même contenu déjà synthétisé, sans toucher au graphe ni à un éventuel
                // contenu que QA aurait modifié depuis.
                if (stepRepository.findByProcedureOrderByStepNumberAsc(existing).isEmpty()) {
                    saveStepsFromNodes(existing, s.nodes());
                    backfilled++;
                }
                continue;
            }

            ProcedureZone zone = zoneRepository.findByCode(s.zoneCode()).orElse(null);
            if (zone == null) continue;

            Procedure procedure = procedureRepository.save(Procedure.builder()
                    .zone(zone).title(s.title()).countryCode(s.countryCode()).build());

            buildGraph(procedure, s.nodes());
            saveStepsFromNodes(procedure, s.nodes());
            created++;
        }
        if (backfilled > 0) {
            log.warn("⚠ [KB IMPORT] {} parcours synthétisé(s) complété(s) avec leurs étapes manquantes (visualiseur \"Voir\").", backfilled);
        }
        return created;
    }

    /** Étapes à plat (lues par le visualiseur "Voir" / ProcedureService) — même contenu que le
     *  graphe branchant, une ligne par nœud, dans l'ordre où il apparaît dans la synthèse. */
    private void saveStepsFromNodes(Procedure procedure, List<ProcessNode> nodes) {
        int n = 1;
        for (ProcessNode node : nodes) {
            stepRepository.save(ProcedureStep.builder()
                    .procedure(procedure).stepNumber(n++).content(node.text()).build());
        }
    }

    /** Construit un vrai graphe (branchements multiples) à partir des identifiants de nœuds du JSON. */
    private void buildGraph(Procedure procedure, List<ProcessNode> nodes) {
        Map<String, ProcedureWorkflowNode> byId = new java.util.LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            ProcessNode n = nodes.get(i);
            byId.put(n.id(), nodeRepository.save(ProcedureWorkflowNode.builder()
                    .procedure(procedure).questionText(n.text()).isStart(i == 0).build()));
        }
        for (ProcessNode n : nodes) {
            ProcedureWorkflowNode current = byId.get(n.id());
            for (ProcessOption opt : n.options()) {
                optionRepository.save(ProcedureWorkflowOption.builder()
                        .node(current).label(opt.label())
                        .nextNode(opt.next() != null ? byId.get(opt.next()) : null)
                        .outcome(opt.outcome())
                        .build());
            }
        }
    }

    /**
     * Crée les fiches pour les motifs du référentiel RAF AI RCC360 qui n'avaient encore aucune
     * fiche (fraude, carte volée/perdue, retrait non servi, débit sans dispense...) — additif
     * uniquement : ne crée rien si une fiche de ce titre existe déjà (correspondance exacte).
     */
    private int seedNewReferentialProcedures() throws Exception {
        ClassPathResource resource = new ClassPathResource("data/procedure-new-entries.json");
        if (!resource.exists()) return 0;
        List<NewProcedureSeed> seeds = load("data/procedure-new-entries.json", NewProcedureSeed.class);
        int created = 0;
        for (NewProcedureSeed s : seeds) {
            boolean alreadyExists = procedureRepository.findAllByOrderByTitleAsc().stream()
                    .anyMatch(p -> p.getTitle().trim().equalsIgnoreCase(s.title()));
            if (alreadyExists) continue;

            ProcedureZone zone = zoneRepository.findByCode(s.zoneCode()).orElse(null);
            if (zone == null) continue;

            Procedure procedure = procedureRepository.save(Procedure.builder()
                    .zone(zone).countryCode(s.countryCode())
                    .title(s.title()).slaDelay(s.slaDelay()).level(s.level()).responsibleTeam(s.responsibleTeam())
                    .build());

            int n = 1;
            for (String step : s.steps()) {
                stepRepository.save(ProcedureStep.builder().procedure(procedure).stepNumber(n++).content(step).build());
            }
            created++;
        }
        return created;
    }

    // ===================== SLA / NIVEAU / ÉQUIPE (référentiel RAF AI RCC360) =====================

    /**
     * Complète le SLA/niveau/équipe responsable de fiches déjà réelles, à partir du référentiel
     * fourni par QA (motif → équipe → niveau → SLA). Correspondance par titre EXACT uniquement
     * (pas de correspondance approximative qui risquerait de coller le mauvais SLA à la mauvaise
     * fiche) — et ne touche JAMAIS un champ déjà renseigné (que ce soit par ce bootstrap lors d'un
     * démarrage précédent, ou modifié depuis par QA) : uniquement du remplissage, jamais d'écrasement.
     */
    private int updateProcedureSlaFields() throws Exception {
        ClassPathResource resource = new ClassPathResource("data/procedure-sla-updates.json");
        if (!resource.exists()) return 0;
        Map<String, SlaUpdate> updates;
        try (InputStream in = resource.getInputStream()) {
            updates = objectMapper.readValue(in, objectMapper.getTypeFactory()
                    .constructMapType(java.util.LinkedHashMap.class, String.class, SlaUpdate.class));
        }

        int updated = 0;
        for (Procedure p : procedureRepository.findAll()) {
            SlaUpdate u = updates.get(p.getTitle().trim());
            if (u == null) continue;
            boolean changed = false;
            if (p.getSlaDelay() == null && u.slaDelay() != null) { p.setSlaDelay(u.slaDelay()); changed = true; }
            if (p.getLevel() == null && u.level() != null) { p.setLevel(u.level()); changed = true; }
            if (p.getResponsibleTeam() == null && u.responsibleTeam() != null) { p.setResponsibleTeam(u.responsibleTeam()); changed = true; }
            if (changed) { procedureRepository.save(p); updated++; }
        }
        return updated;
    }

    // ===================== HELPERS =====================

    private <T> List<T> load(String path, Class<T> type) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) return List.of();
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, objectMapper.getTypeFactory().constructCollectionType(List.class, type));
        }
    }
}
