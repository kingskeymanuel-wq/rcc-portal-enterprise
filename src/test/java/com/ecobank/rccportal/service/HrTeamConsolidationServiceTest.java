package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** RCC = enceinte, pas une équipe : les comptes RCC / sans équipe rejoignent l'équipe de leur double. */
class HrTeamConsolidationServiceTest {

    private static User user(long id, String username, String name, String email, String activity) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setName(name);
        u.setEmail(email);
        u.setActivity(activity);
        u.setAccountEnabled(true);
        return u;
    }

    private final UserRepository repo = mock(UserRepository.class);
    private final HrTeamConsolidationService service = new HrTeamConsolidationService(repo);

    private final List<User> users = List.of(
            user(1, "akone", "Awa KONÉ", "awa.kone@ecobank.com", "INBOUND_VOICE"),
            user(2, "E1001", "KONE Awa", null, "Rcc"),                              // même personne (nom inversé, accent)
            user(3, "ydiallo", "Yao Diallo", "y.diallo@ecobank.com", "OUTBOUND"),
            user(4, "yao.d", "Y. Diallo", "Y.Diallo@ecobank.com", null),            // même e-mail
            user(5, "nouveau", "Marie Nouvelle", null, null),                         // personne sans double
            user(6, "fsow", "Fatou Sow", null, "CIB"));

    @Test
    void previewMatchesByNameAndEmailAndLeavesRealTeamsAlone() {
        when(repo.findAll()).thenReturn(users);
        HrTeamConsolidationService.Report r = service.preview();
        assertEquals(2, r.moves().size());
        assertEquals("INBOUND VOICE", r.moves().stream().filter(m -> m.username().equals("E1001")).findFirst().orElseThrow().toTeam());
        HrTeamConsolidationService.Move byMail = r.moves().stream().filter(m -> m.username().equals("yao.d")).findFirst().orElseThrow();
        assertEquals("OUTBOUND", byMail.toTeam());
        assertEquals("e-mail", byMail.matchedBy());
        assertEquals(List.of("Marie Nouvelle (nouveau)"), r.unmatched());
        assertEquals(6, r.totalAccounts());
        assertEquals(4, r.people());
        assertFalse(r.applied());
        verify(repo, never()).save(any());
    }

    @Test
    void applyOnlySetsTheTeamOfUnassignedAccounts() {
        when(repo.findAll()).thenReturn(users);
        service.apply();
        assertEquals("INBOUND VOICE", users.get(1).getActivity());
        assertEquals("OUTBOUND", users.get(3).getActivity());
        assertNull(users.get(4).getActivity());
        assertEquals("CIB", users.get(5).getActivity());
        verify(repo, times(2)).save(any());
    }

    @Test
    void rccIsTheUmbrellaNotATeam() {
        assertTrue(HrTeamConsolidationService.isUmbrella("Rcc"));
        assertTrue(HrTeamConsolidationService.isUmbrella("  "));
        assertTrue(HrTeamConsolidationService.isUmbrella("Sans équipe"));
        assertFalse(HrTeamConsolidationService.isUmbrella("INBOUND"));
        assertEquals("", HrTeamConsolidationService.nameKey("Awa"), "un seul mot ne suffit pas à rapprocher deux comptes");
    }

    @Test
    void homonymsInTwoTeamsAreNeverGuessed() {
        List<User> list = List.of(
                user(1, "a1", "Jean Kouassi", null, "INBOUND"),
                user(2, "a2", "Jean Kouassi", null, "OUTBOUND"),
                user(3, "a3", "KOUASSI Jean", null, "Rcc"));
        when(repo.findAll()).thenReturn(list);
        HrTeamConsolidationService.Report r = service.preview();
        assertTrue(r.moves().isEmpty());
        assertTrue(r.unmatched().get(0).contains("homonymes"));
    }
}
