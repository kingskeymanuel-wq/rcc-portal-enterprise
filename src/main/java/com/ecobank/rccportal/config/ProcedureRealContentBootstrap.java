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
 * Remplace le contenu provisoire ("En attente de rédaction par QA") posé par
 * ProcedureSeedBootstrap par le vrai contenu — extrait automatiquement du
 * document "UNIFORMISATION DES PROCEDURES DE PRISE EN CHARGE RCC" (PDF,
 * 129 pages, un diagramme de parcours par page). L'extraction a repéré
 * chaque boîte du diagramme (position + couleur), reconstitué l'ordre réel
 * du parcours (motif "en serpent" : une ligne de gauche à droite, la
 * suivante de droite à gauche — vérifié visuellement sur plusieurs pages
 * avant d'être généralisé), et fait correspondre chaque titre extrait à une
 * procédure déjà en base (120 correspondances fiables sur 121 — un titre
 * sans correspondance a été ajouté comme nouvelle procédure).
 *
 * Les données extraites vivent dans data/procedure-real-content.json (pas
 * codées en dur ici) — un futur réimport avec un PDF mis à jour n'aura qu'à
 * remplacer ce fichier.
 *
 * Idempotent et PRUDENT : une procédure n'est mise à jour que si elle a
 * encore exactement son contenu provisoire d'origine ET aucun parcours
 * interactif — si QA a déjà écrit ou modifié quoi que ce soit dessus
 * entre-temps, on ne touche à rien (son travail prime sur l'import
 * automatique).
 */
@Slf4j
@Component
@Order(21) // après ProcedureSeedBootstrap (Order 20), qui crée les procédures placeholder
public class ProcedureRealContentBootstrap implements CommandLineRunner {

    private static final String PLACEHOLDER_MARKER = "En attente de rédaction par QA";

    private record ProcedureContent(String title, boolean isNew, String zoneHint, List<String> steps) {
    }

    private final ProcedureRepository procedureRepository;
    private final ProcedureZoneRepository zoneRepository;
    private final ProcedureStepRepository stepRepository;
    private final ProcedureWorkflowNodeRepository nodeRepository;
    private final ProcedureWorkflowOptionRepository optionRepository;
    private final ObjectMapper objectMapper;

    public ProcedureRealContentBootstrap(
            ProcedureRepository procedureRepository,
            ProcedureZoneRepository zoneRepository,
            ProcedureStepRepository stepRepository,
            ProcedureWorkflowNodeRepository nodeRepository,
            ProcedureWorkflowOptionRepository optionRepository,
            ObjectMapper objectMapper) {
        this.procedureRepository = procedureRepository;
        this.zoneRepository = zoneRepository;
        this.stepRepository = stepRepository;
        this.nodeRepository = nodeRepository;
        this.optionRepository = optionRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        List<ProcedureContent> entries = loadContent();
        if (entries == null || entries.isEmpty()) return;

        Map<String, Procedure> byTitle = new java.util.HashMap<>();
        for (Procedure p : procedureRepository.findAll()) {
            byTitle.put(p.getTitle().trim().toLowerCase(), p);
        }

        int updated = 0, created = 0, skipped = 0;

        for (ProcedureContent entry : entries) {
            if (entry.steps() == null || entry.steps().isEmpty()) continue;

            Procedure procedure = byTitle.get(entry.title().trim().toLowerCase());

            if (procedure == null) {
                if (!entry.isNew() || entry.zoneHint() == null) continue;
                ProcedureZone zone = zoneRepository.findByCode(entry.zoneHint()).orElse(null);
                if (zone == null) continue;
                procedure = procedureRepository.save(Procedure.builder().zone(zone).title(entry.title()).build());
                created++;
            } else {
                boolean stillPlaceholder = stepRepository.findByProcedureOrderByStepNumberAsc(procedure).stream()
                        .allMatch(s -> s.getContent() != null && s.getContent().contains(PLACEHOLDER_MARKER));
                boolean noWorkflowYet = nodeRepository.findByProcedure(procedure).isEmpty();
                if (!stillPlaceholder || !noWorkflowYet) {
                    skipped++;
                    continue; // QA a déjà touché cette fiche — on ne l'écrase pas
                }
                stepRepository.deleteByProcedure(procedure);
                updated++;
            }

            saveRealSteps(procedure, entry.steps());
            saveRealWorkflow(procedure, entry.steps());
        }

        if (updated > 0 || created > 0) {
            log.warn("⚠ [PROCEDURE REAL CONTENT] {} fiche(s) mise(s) à jour avec le vrai contenu du PDF, {} créée(s), {} ignorée(s) (déjà modifiées par QA).",
                    updated, created, skipped);
        }
    }

    private void saveRealSteps(Procedure procedure, List<String> steps) {
        int n = 1;
        for (String step : steps) {
            stepRepository.save(ProcedureStep.builder()
                    .procedure(procedure).stepNumber(n++).content(step).build());
        }
    }

    /** Même construction linéaire que le modèle "Consultation de solde" — un nœud par étape, la dernière est le nœud terminal. */
    private void saveRealWorkflow(Procedure procedure, List<String> steps) {
        ProcedureWorkflowNode previous = null;
        ProcedureWorkflowNode first = null;

        for (int i = 0; i < steps.size(); i++) {
            ProcedureWorkflowNode node = nodeRepository.save(ProcedureWorkflowNode.builder()
                    .procedure(procedure).questionText(steps.get(i)).isStart(i == 0).build());
            if (first == null) first = node;

            if (previous != null) {
                optionRepository.save(ProcedureWorkflowOption.builder()
                        .node(previous).label("Étape suivante").nextNode(node).build());
            }
            previous = node;
        }

        if (previous != null) {
            optionRepository.save(ProcedureWorkflowOption.builder()
                    .node(previous).label("Incident clôturé").outcome("CLOTURE").build());
        }
    }

    private List<ProcedureContent> loadContent() throws Exception {
        ClassPathResource resource = new ClassPathResource("data/procedure-real-content.json");
        if (!resource.exists()) return List.of();
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, objectMapper.getTypeFactory().constructCollectionType(List.class, ProcedureContent.class));
        }
    }
}
