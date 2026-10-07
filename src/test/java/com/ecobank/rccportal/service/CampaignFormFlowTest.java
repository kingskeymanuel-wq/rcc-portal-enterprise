package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.CampaignRequest;
import com.ecobank.rccportal.dto.CampaignResponse;
import com.ecobank.rccportal.dto.UpdateCallStatusRequest;
import com.ecobank.rccportal.model.Campaign;
import com.ecobank.rccportal.model.CampaignContact;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.CampaignContactRepository;
import com.ecobank.rccportal.repository.CampaignRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Campagne avec formulaire v2 : création, appel (contrôles + score serveur), résultats et export. */
class CampaignFormFlowTest {

    CampaignRepository campaignRepo = mock(CampaignRepository.class);
    CampaignContactRepository contactRepo = mock(CampaignContactRepository.class);
    UserRepository userRepo = mock(UserRepository.class);
    Map<Integer, Campaign> campaigns = new HashMap<>();
    Map<Integer, CampaignContact> contacts = new LinkedHashMap<>();
    CampaignService service;
    CampaignResultsService results;

    // Team Leader de la sous-équipe Télévente : doit pouvoir gérer les campagnes.
    AuthenticatedUser tl = new AuthenticatedUser("tl.televente", "TEAM_LEADER", "TEAM_LEADER_TELEVENTE", "TL");
    AuthenticatedUser agent = new AuthenticatedUser("agent.tv", "AGENT", "AGENT_TELEVENTE", "Agent");

    @BeforeEach
    void setUp() {
        User tlUser = User.builder().id(1L).username("tl.televente").name("TL Télévente").ledTeam("TELEVENTE").build();
        User agentUser = User.builder().id(2L).username("agent.tv").name("Awa Agent").activity("OUTBOUND TELEVENTE").build();
        when(userRepo.findFirstByUsernameIgnoreCase("tl.televente")).thenReturn(Optional.of(tlUser));
        when(userRepo.findFirstByUsernameIgnoreCase("agent.tv")).thenReturn(Optional.of(agentUser));
        when(userRepo.findById(2L)).thenReturn(Optional.of(agentUser));
        when(campaignRepo.save(any(Campaign.class))).thenAnswer(i -> {
            Campaign c = i.getArgument(0);
            if (c.getCampaignId() == null) c.setCampaignId(campaigns.size() + 1);
            campaigns.put(c.getCampaignId(), c);
            return c;
        });
        when(campaignRepo.findById(anyInt())).thenAnswer(i -> Optional.ofNullable(campaigns.get((Integer) i.getArgument(0))));
        when(contactRepo.findById(anyInt())).thenAnswer(i -> Optional.ofNullable(contacts.get((Integer) i.getArgument(0))));
        when(contactRepo.save(any(CampaignContact.class))).thenAnswer(i -> i.getArgument(0));
        when(contactRepo.findByCampaignIdOrderByClientNameAsc(anyInt())).thenAnswer(i -> contacts.values().stream()
                .filter(c -> c.getCampaignId().equals(i.getArgument(0))).toList());
        service = new CampaignService(campaignRepo, contactRepo, userRepo, new ObjectMapper().findAndRegisterModules());
        results = new CampaignResultsService(campaignRepo, contactRepo, userRepo, service);
    }

    CampaignContact contact(int id, int campaignId, String name) {
        CampaignContact c = CampaignContact.builder().contactId(id).campaignId(campaignId).agentUserId(2L).clientName(name).clientPhone("+2250700000000").build();
        contacts.put(id, c);
        return c;
    }

    @Test
    void fullFlow() {
        CampaignResponse created = service.createCampaign(tl, new CampaignRequest("Carte Visa — octobre", null, null, null, "TELEVENTE",
                null, null, null, null, null, CampaignFormTemplates.byCode("CARTE").orElseThrow().form()));
        assertNotNull(created.form());
        assertFalse(created.fields().isEmpty(), "liste plate tenue à jour pour l'import de fichiers");

        contact(10, created.campaignId(), "Koffi Jean");
        contact(11, created.campaignId(), "Yao Marie");

        // Réponse incomplète refusée pour une issue finale, message exact.
        ApiException ex = assertThrows(ApiException.class, () -> service.updateCallStatus(agent, 10,
                new UpdateCallStatusRequest("GREEN", null, Map.of("joignable", "Oui, disponible"))));
        assertTrue(ex.getMessage().contains("obligatoire"), ex.getMessage());

        Map<String, String> answers = new HashMap<>(Map.of("joignable", "Oui, disponible", "carte_actuelle", "Non, aucune carte",
                "usages", "[\"Paiements en ligne\",\"Voyages / devises\"]", "offre", "Visa Gold", "decision", "Souhaite un RDV en agence",
                "agence", "Cocody", "consent", "true", "objection", "masquée : retirée"));
        var res = service.updateCallStatus(agent, 10, new UpdateCallStatusRequest("YELLOW", "OK", answers));
        assertNotNull(res.leadScore());
        assertTrue(res.leadScore() >= 55, "lead qualifié : " + res.leadScore());
        assertFalse(res.answers().containsKey("objection"));

        service.updateCallStatus(agent, 11, new UpdateCallStatusRequest("RED", null, Map.of("joignable", "Mauvais numéro")));

        Map<String, Object> r = results.results(tl, created.campaignId());
        assertEquals(1L, ((Map<?, ?>) r.get("statuses")).get("YELLOW"));
        assertEquals(1L, ((Map<?, ?>) r.get("statuses")).get("RED"));
        List<?> funnel = (List<?>) r.get("funnel");
        assertEquals(2L, ((Map<?, ?>) funnel.get(0)).get("count"));
        assertEquals(1L, ((Map<?, ?>) funnel.get(5)).get("count"), "un lead qualifié");
        List<?> questions = (List<?>) r.get("questions");
        Map<?, ?> offre = questions.stream().map(q -> (Map<?, ?>) q).filter(q -> "offre".equals(q.get("id"))).findFirst().orElseThrow();
        assertTrue(offre.get("options").toString().contains("Visa Gold"));

        String csv = new String(results.exportCsv(tl, created.campaignId()), StandardCharsets.UTF_8);
        assertTrue(csv.contains("Koffi Jean") && csv.contains("Paiements en ligne, Voyages / devises") && csv.contains("RDV pris"), csv);
        assertTrue(csv.contains(";+2250700000000;"), "numéro conservé tel quel");

        // Duplication : formulaire repris, aucun contact.
        CampaignResponse copy = service.duplicateCampaign(tl, created.campaignId());
        assertEquals(created.form().get("sections").size(), copy.form().get("sections").size());
        assertEquals(0, copy.totalContacts());
    }

    @Test
    void agentsCannotManage() {
        assertThrows(ApiException.class, () -> service.requireCanManage(agent));
        assertDoesNotThrow(() -> service.requireCanManage(tl));
    }
}
