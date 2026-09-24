package com.ecobank.rccportal.config;

import com.ecobank.rccportal.model.BankBranch;
import com.ecobank.rccportal.repository.BankBranchRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Amorce l'onglet "Carte des banques" (voir BankBranch / BankBranchController /
 * bank-map.js) à partir de data/bank-branches.json :
 * - Côte d'Ivoire (CI) : agences réelles (Plateau/Siège, Adjamé, Abobo, Cocody,
 *   Aboisso, San-Pédro) — adresses vérifiées par recherche web, coordonnées
 *   approximatives au niveau commune/ville (pas de géocodage précis Google —
 *   voir contrainte "on ne s'appuie pas sur Google Maps"), à affiner via le
 *   formulaire admin (recherche d'adresse OpenStreetMap intégrée).
 * - 19 autres filiales : UN SEUL repère par pays (capitale ou ville économique
 *   principale, coordonnées officielles), explicitement marqué "À compléter" —
 *   aucune agence détaillée n'a été inventée pour ces filiales ; à chaque
 *   équipe locale de compléter via Administration > Carte des banques.
 *
 * Idempotent PAR FILIALE : si une filiale a déjà au moins une agence en base
 * (qu'elle vienne de ce seed ou d'un ajout admin), elle est entièrement
 * ignorée au redémarrage — ne touche jamais aux données déjà saisies/modifiées
 * par les équipes locales.
 */
@Slf4j
@Component
@Order(24)
public class BankBranchSeedBootstrap implements CommandLineRunner {

    private record BranchSeed(String countryCode, String city, String name, String address,
                               Double latitude, Double longitude, String phone, String email,
                               String openingHours, String managerName, String branchType, Boolean ensure,
                               List<String> replaces) {}

    /** Code agence en fin de nom : « Agence Daloa (K13) » → « K13 ». */
    private static final java.util.regex.Pattern CODE_IN_NAME = java.util.regex.Pattern.compile("\\(([A-Z]{1,3}\\d{1,4})\\)\\s*$");

    /** Agences semées par erreur par une version précédente (absentes de la liste officielle) : désactivées. */
    private static final Set<String> RETIRED_CI = Set.of("Agence Cocody — ENA");

    private final BankBranchRepository branchRepository;
    private final ObjectMapper objectMapper;

    public BankBranchSeedBootstrap(BankBranchRepository branchRepository, ObjectMapper objectMapper) {
        this.branchRepository = branchRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        try {
            int created = seedBranches();
            if (created > 0) {
                log.warn("⚠ [BANK MAP] {} agence(s) de départ créée(s) (filiales sans aucune agence existante).", created);
            }
        } catch (Exception e) {
            // Table BankBranches absente (normalement créée par WorkflowSchemaBootstrap, Order 1) — pas bloquant,
            // réessaiera au prochain démarrage.
            log.warn("⚠ [BANK MAP] Import ignoré pour l'instant (table manquante ?) : {}", e.getMessage());
        }
    }

    /** Pas de @Transactional ici : self-invocation depuis run() dans la même classe —
     *  le proxy Spring ne l'interceptrait de toute façon pas (même absence volontaire
     *  que KnowledgeBaseImportBootstrap.seedCountries() et consorts). Chaque
     *  branchRepository.save() reste transactionnel individuellement (Spring Data JPA). */
    private int seedBranches() throws Exception {
        List<BranchSeed> seeds = load("data/bank-branches.json", BranchSeed.class);
        if (seeds.isEmpty()) return 0;

        Set<String> alreadySeeded = new HashSet<>(); // évite N requêtes existsBy... pour un même pays répété dans le fichier
        Set<String> seededNow = new HashSet<>();     // filiales entièrement créées pendant ce passage
        int created = 0;
        for (BranchSeed s : seeds) {
            String code = s.countryCode().toUpperCase();
            if (alreadySeeded.contains(code)) continue;
            if (branchRepository.existsByCountryCodeIgnoreCase(code)) {
                alreadySeeded.add(code); // filiale déjà alimentée (seed précédent ou saisie admin) — on ne touche à rien
                continue;
            }
            // Première agence de cette filiale dans ce passage : on insère TOUTES ses entrées du seed d'un coup,
            // puis on la marque comme traitée pour ne pas la re-tester à chaque ligne suivante.
            for (BranchSeed s2 : seeds) {
                if (!s2.countryCode().equalsIgnoreCase(code)) continue;
                branchRepository.save(BankBranch.builder()
                        .countryCode(code)
                        .city(s2.city())
                        .name(s2.name())
                        .address(s2.address())
                        .latitude(s2.latitude())
                        .longitude(s2.longitude())
                        .phone(s2.phone())
                        .email(s2.email())
                        .openingHours(s2.openingHours())
                        .managerName(s2.managerName())
                        .branchType(s2.branchType())
                        .active(true)
                        .build());
                created++;
            }
            seededNow.add(code);
            alreadySeeded.add(code);
        }
        // Agences marquées "ensure" : ajoutées même si la filiale a déjà des agences (ex. agences CI
        // du point de disponibilité des cartes), une seule fois — jamais si une agence du même nom existe.
        java.util.Map<String, List<BankBranch>> existingByCountry = new java.util.HashMap<>();
        for (BranchSeed s : seeds) {
            if (!Boolean.TRUE.equals(s.ensure())) continue;
            String code = s.countryCode().toUpperCase();
            if (seededNow.contains(code)) continue; // déjà insérée avec toute la filiale ci-dessus
            if (branchRepository.existsByCountryCodeIgnoreCaseAndNameIgnoreCase(code, s.name())) continue;
            List<BankBranch> existing = existingByCountry.computeIfAbsent(code, branchRepository::findByCountryCodeIgnoreCaseOrderByCityAscNameAsc);
            if (existing == null) existing = List.of();
            // Même code agence déjà présent sous un autre libellé (« … (K13) ») : jamais de doublon.
            String agencyCode = agencyCode(s.name());
            if (agencyCode != null && existing.stream().anyMatch(b -> agencyCode.equals(agencyCode(b.getName())))) continue;
            // Ancienne entrée sans code (« Agence Aboisso ») : on la RENOMME au lieu d'en créer une seconde.
            BankBranch old = s.replaces() == null ? null : existing.stream()
                    .filter(b -> s.replaces().stream().anyMatch(r -> r.equalsIgnoreCase(b.getName())))
                    .findFirst().orElse(null);
            if (old != null) {
                old.setName(s.name());
                if (old.getCity() == null || old.getCity().isBlank()) old.setCity(s.city());
                if (s.branchType() != null) old.setBranchType(s.branchType());
                old.setActive(true);
                branchRepository.save(old);
                continue;
            }
            branchRepository.save(BankBranch.builder()
                    .countryCode(code).city(s.city()).name(s.name()).address(s.address())
                    .latitude(s.latitude()).longitude(s.longitude()).phone(s.phone()).email(s.email())
                    .openingHours(s.openingHours()).managerName(s.managerName()).branchType(s.branchType())
                    .active(true).build());
            created++;
        }
        for (BankBranch b : existingByCountry.getOrDefault("CI", List.of())) {
            if (b.isActive() && RETIRED_CI.contains(b.getName())) {
                b.setActive(false);
                branchRepository.save(b);
            }
        }
        return created;
    }

    static String agencyCode(String name) {
        if (name == null) return null;
        java.util.regex.Matcher m = CODE_IN_NAME.matcher(name.trim().toUpperCase());
        return m.find() ? m.group(1) : null;
    }

    private <T> List<T> load(String path, Class<T> type) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) return List.of();
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, objectMapper.getTypeFactory().constructCollectionType(List.class, type));
        }
    }
}
