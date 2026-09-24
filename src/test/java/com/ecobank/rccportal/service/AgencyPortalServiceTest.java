package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.CardAgencyDtos.AgencyReport;
import com.ecobank.rccportal.model.BankBranch;
import com.ecobank.rccportal.model.CardAgencyStatus;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.BankBranchRepository;
import com.ecobank.rccportal.repository.CardAgencyStatusRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgencyPortalServiceTest {

    private static CardAgencyStatus row(String agency, String code, LocalDate d, String card) {
        CardAgencyStatus s = new CardAgencyStatus();
        s.setCountryCode("CI"); s.setAgency(agency); s.setAgencyCode(code); s.setReportDate(d);
        s.setCardStatus(card); s.setPinStatus("OK"); s.setCardTypes("CLASSIC");
        return s;
    }

    @Test
    void currentSituationKeepsTheLatestUpdateOfEachAgency() {
        CardAgencyStatusRepository repo = mock(CardAgencyStatusRepository.class);
        LocalDate today = LocalDate.of(2026, 9, 25), before = LocalDate.of(2026, 9, 24);
        // Trié par date décroissante, comme la requête réelle.
        when(repo.findByCountryCodeIgnoreCaseOrderByReportDateDescAgencyAsc("CI")).thenReturn(List.of(
                row("AGENCE YOP NIANGON", "K27", today, "RUPTURE"),
                row("NIANGON", "K27", before, "OK"),
                row("DALOA", "K13", before, "OK")));
        CardAgencyStatusService svc = new CardAgencyStatusService(repo);
        AgencyReport current = svc.report("CI", null);
        assertTrue(current.current());
        assertEquals(2, current.rows().size());                 // Daloa ne disparaît pas quand Niangon coche aujourd'hui
        assertEquals("RUPTURE", current.rows().stream().filter(r -> "K27".equals(r.agencyCode())).findFirst().orElseThrow().cardStatus());
        assertEquals(2, svc.report("CI", before).rows().size()); // l'historique par date reste consultable
        assertEquals("RUPTURE", svc.currentFor("CI", "k27").orElseThrow().cardStatus());
    }

    private record Fixture(AgencyPortalService svc, JdbcTemplate jdbc, CardAgencyStatusRepository cards) {}

    private static Fixture fixture(boolean alreadyAssigned) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UserRepository users = mock(UserRepository.class);
        BankBranchRepository branches = mock(BankBranchRepository.class);
        CardAgencyStatusRepository cards = mock(CardAgencyStatusRepository.class);
        User u = new User(); u.setId(7L); u.setUsername("akone");
        when(users.findFirstByUsernameIgnoreCase(anyString())).thenReturn(Optional.of(u));
        BankBranch b = BankBranch.builder().branchId(3L).countryCode("CI").name("Agence Yop Niangon (K27)").active(true).build();
        when(branches.findById(3L)).thenReturn(Optional.of(b));
        when(branches.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jdbc.queryForList(startsWith("SELECT BranchId"), eq(7L)))
                .thenReturn(alreadyAssigned ? List.of(Map.of("BranchId", 3L)) : List.of());
        when(cards.findByCountryCodeIgnoreCaseOrderByReportDateDescAgencyAsc(anyString())).thenReturn(List.of());
        when(cards.save(any())).thenAnswer(i -> i.getArgument(0));
        AgencyPortalService svc = new AgencyPortalService(jdbc, users, branches, new CardAgencyStatusService(cards), new DataProtectionService(), new AtmStatusService(jdbc));
        return new Fixture(svc, jdbc, cards);
    }

    private static final AuthenticatedUser STAFF = new AuthenticatedUser("akone", "AGENCE", "AGENCE_CAISSIER", "Awa Koné");

    @Test
    void agencyIsChosenOnceThenLocked() {
        Fixture f = fixture(true);
        ApiException e = assertThrows(ApiException.class, () -> f.svc().chooseMyAgency(STAFF, 3L));
        assertTrue(e.getMessage().contains("administrateur"));
        assertThrows(ApiException.class, () -> fixture(false).svc().chooseMyAgency(
                new AuthenticatedUser("x", "AGENT", "AGENT_INBOUND", "X"), 3L)); // un agent RCC ne se rattache pas à une agence
    }

    @Test
    void availabilityIsPublishedForMyAgencyOnlyAndNeverWithClientData() {
        Fixture f = fixture(true);
        var saved = f.svc().updateMyAvailability(STAFF, new AgencyPortalService.AvailabilityRequest("OK", "faible", List.of("gold", "Classic"), "Livraison jeudi"));
        assertEquals("K27", saved.agencyCode());
        assertEquals("FAIBLE", saved.pinStatus());
        assertEquals(List.of("GOLD", "CLASSIC"), saved.cardTypes());
        assertTrue(saved.updatedBy().contains("K27"));

        ApiException e = assertThrows(ApiException.class, () -> f.svc().updateMyAvailability(STAFF,
                new AgencyPortalService.AvailabilityRequest("OK", "OK", List.of(), "Rappeler le client au 0708091011")));
        assertTrue(e.getMessage().contains("aucune donnée client"));

        assertThrows(ApiException.class, () -> fixture(false).svc().updateMyAvailability(STAFF,
                new AgencyPortalService.AvailabilityRequest("OK", "OK", List.of(), null))); // pas encore d'agence
    }

    @Test
    void atmStatusIsPublishedForMyAgencyWithoutClientData() {
        Fixture f = fixture(true);
        var atm = f.svc().updateMyAtm(STAFF, new AtmStatusService.AtmRequest("SANS_BILLETS", List.of("DEPOT"), 2, 2, "Recharge prévue à 14h"));
        assertEquals("SANS_BILLETS", atm.status());
        assertEquals("K27", atm.agencyCode());
        ApiException e = assertThrows(ApiException.class, () -> f.svc().updateMyAtm(STAFF,
                new AtmStatusService.AtmRequest("EN_SERVICE", List.of(), null, null, "client joignable au 0102030405")));
        assertTrue(e.getMessage().contains("aucune donnée client"));
    }

    @Test
    void agencyCodeIsReadFromTheBranchName() {
        assertEquals("K27", AgencyPortalService.agencyCode("Agence Yop Niangon (K27)"));
        assertNull(AgencyPortalService.agencyCode("Ecobank Burkina Faso — repère centre-ville"));
    }
}
