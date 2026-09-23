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
                               String openingHours, String managerName, String branchType) {}

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
            // Table BankBranches pas encore créée (migration 012 pas exécutée) — pas bloquant, réessaiera au prochain démarrage.
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
            alreadySeeded.add(code);
        }
        return created;
    }

    private <T> List<T> load(String path, Class<T> type) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) return List.of();
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, objectMapper.getTypeFactory().constructCollectionType(List.class, type));
        }
    }
}
