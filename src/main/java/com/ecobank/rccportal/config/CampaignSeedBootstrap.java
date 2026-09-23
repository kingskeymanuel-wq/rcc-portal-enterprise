package com.ecobank.rccportal.config;

import com.ecobank.rccportal.dto.CampaignFieldDto;
import com.ecobank.rccportal.model.Campaign;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.CampaignRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Crée 3 campagnes Outbound de démonstration au premier démarrage (onglet "Campagne" agent —
 * voir les captures utilisateur : écran vide "Aucune campagne active ne vous est ouverte pour
 * l'instant" tant qu'aucun Team Leader Outbound n'en a créé une manuellement). N'ajoute rien si
 * la table contient déjà au moins une campagne (le Team Leader a pris la main).
 *
 * Reprend les caractéristiques déjà construites : photo de couverture (bibliothèque partagée
 * avec le sélecteur JS — voir CAMPAIGN_COVER_LIBRARY dans outbound-dashboard.js), questions
 * dynamiques de plusieurs types (texte, choix, y compris le nouveau type LINK).
 */
@Slf4j
@Component
@Order(24) // après LoginFeatureCardSeedBootstrap (Order 23)
public class CampaignSeedBootstrap implements CommandLineRunner {

    private final CampaignRepository campaignRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public CampaignSeedBootstrap(CampaignRepository campaignRepository, UserRepository userRepository,
                                  ObjectMapper objectMapper) {
        this.campaignRepository = campaignRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        try {
            if (campaignRepository.count() > 0) return;

            Long ownerId = resolveOwnerId();
            if (ownerId == null) {
                log.warn("⚠ [CAMPAIGNS SEED] Aucun utilisateur en base — campagnes par défaut reportées au prochain démarrage.");
                return;
            }

            campaignRepository.save(Campaign.builder()
                    .name("Carte Bancaire")
                    .description("Campagne d'appels pour la souscription et l'activation de cartes bancaires.")
                    .createdByUserId(ownerId)
                    .status("ACTIVE")
                    .targetService(null)
                    .iconClass("bi-credit-card-fill")
                    .colorFrom("#0057B8").colorTo("#00A651")
                    .coverImageUrl("https://images.unsplash.com/photo-1556742049-0cfed4f6a45d?w=400&h=260&fit=crop")
                    .fieldsJson(serialize(List.of(
                            new CampaignFieldDto("f1", "Le client est-il intéressé par la carte ?", "RADIO",
                                    List.of("Oui", "Non", "À rappeler plus tard"), true),
                            new CampaignFieldDto("f2", "Commentaire libre", "TEXTAREA", List.of(), false)
                    )))
                    .build());

            campaignRepository.save(Campaign.builder()
                    .name("Prêt Scolaire & Conso 2026")
                    .description("Campagne d'appels pour la souscription de prêts scolaires et de consommation.")
                    .createdByUserId(ownerId)
                    .status("ACTIVE")
                    .targetService("TELEVENTE")
                    .iconClass("bi-mortarboard-fill")
                    .colorFrom("#7B2CBF").colorTo("#F72585")
                    .coverImageUrl("https://images.unsplash.com/photo-1503676260728-1c00da094a0b?w=400&h=260&fit=crop")
                    .fieldsJson(serialize(List.of(
                            new CampaignFieldDto("f1", "Type de prêt souhaité", "SELECT",
                                    List.of("Scolaire", "Consommation", "Les deux"), true),
                            new CampaignFieldDto("f2", "Le client est-il intéressé ?", "RADIO",
                                    List.of("Oui", "Non"), true),
                            new CampaignFieldDto("f3", "Lien vers le dossier client (si applicable)", "LINK", List.of(), false),
                            new CampaignFieldDto("f4", "Commentaire libre", "TEXTAREA", List.of(), false)
                    )))
                    .build());

            campaignRepository.save(Campaign.builder()
                    .name("Produits Digitaux")
                    .description("Campagne d'appels pour la découverte et l'activation des services bancaires digitaux.")
                    .createdByUserId(ownerId)
                    .status("ACTIVE")
                    .targetService("DIGITAL")
                    .iconClass("bi-phone-fill")
                    .colorFrom("#0096C7").colorTo("#48CAE4")
                    .coverImageUrl("https://images.unsplash.com/photo-1497215728101-856f4ea42174?w=400&h=260&fit=crop")
                    .fieldsJson(serialize(List.of(
                            new CampaignFieldDto("f1", "Le client utilise-t-il déjà l'appli mobile ?", "RADIO",
                                    List.of("Oui", "Non"), true),
                            new CampaignFieldDto("f2", "Date de rappel souhaitée", "DATE", List.of(), false),
                            new CampaignFieldDto("f3", "Commentaire libre", "TEXTAREA", List.of(), false)
                    )))
                    .build());

            log.warn("⚠ [CAMPAIGNS SEED] 3 campagnes Outbound par défaut créées (Carte Bancaire, Prêt Scolaire & Conso 2026, Produits Digitaux).");
        } catch (Exception e) {
            // Table pas encore créée (migration pas exécutée) — pas bloquant, réessaiera au prochain démarrage.
            log.warn("⚠ [CAMPAIGNS SEED] Report : {}", e.getMessage());
        }
    }

    /** Attribue les campagnes seed à un Team Leader Outbound si on en trouve un, sinon au
     *  premier utilisateur disponible — juste pour satisfaire la contrainte CreatedByUserId
     *  (jamais affiché tel quel côté agent). */
    private Long resolveOwnerId() {
        List<User> users = userRepository.findAll();
        return users.stream()
                .filter(u -> "OUTBOUND".equalsIgnoreCase(u.getLedTeam()))
                .findFirst()
                .or(() -> users.stream().findFirst())
                .map(User::getId)
                .orElse(null);
    }

    private String serialize(List<CampaignFieldDto> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            return null;
        }
    }
}
