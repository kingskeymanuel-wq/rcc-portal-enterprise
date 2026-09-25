package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.TeamClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PageUsageServiceTest {

    @Test
    void pagesAreNormalizedToTheirTab() {
        assertEquals("/knowledge", PageUsageService.normalizePage("/knowledge?article=12"));
        assertEquals("/workflow", PageUsageService.normalizePage("/Workflow/"));
        assertEquals("/dashboard", PageUsageService.normalizePage("/"));
        assertEquals("/audit", PageUsageService.normalizePage("/audit.html"));
        assertEquals("/training", PageUsageService.normalizePage("/training/lesson/5"));
        assertNull(PageUsageService.normalizePage("/login"));
        assertNull(PageUsageService.normalizePage("/api"));
        assertNull(PageUsageService.normalizePage("javascript:alert(1)"));
        assertNull(PageUsageService.normalizePage(null));
    }

    @Test
    void pingAddsTimeCappedPerCallAndInsertsTheFirstTime() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UserRepository users = mock(UserRepository.class);
        User u = new User(); u.setId(4L); u.setUsername("yao");
        when(users.findFirstByUsernameIgnoreCase("yao")).thenReturn(Optional.of(u));
        when(jdbc.update(startsWith("UPDATE dbo.PageUsageDaily"), any(Object[].class))).thenReturn(0);
        var svc = new PageUsageService(jdbc, users);
        svc.record(new AuthenticatedUser("yao", "AGENT", null, null), new PageUsageService.PingRequest("/knowledge", 9999, true));
        verify(jdbc).update(startsWith("INSERT INTO dbo.PageUsageDaily"), eq(4L), any(), eq("/knowledge"), eq(300), eq(1), any());
        svc.record(new AuthenticatedUser("yao", "AGENT", null, null), new PageUsageService.PingRequest("/login", 30, false));
        verify(jdbc, times(1)).update(startsWith("INSERT INTO dbo.PageUsageDaily"), any(Object[].class));
    }

    @Test
    void reportIsAdminOnly() {
        var svc = new PageUsageService(mock(JdbcTemplate.class), mock(UserRepository.class));
        assertThrows(ApiException.class, () -> svc.report(new AuthenticatedUser("tl", "TEAM_LEADER", null, null), 60));
    }

    @Test
    void teamFallsBackToServiceCodeWhenActivityIsEmpty() {
        assertEquals(TeamClassifier.Team.INBOUND_VOICE, TeamClassifier.classify(null, List.of("AGENT_INBOUND")));
        assertEquals(TeamClassifier.Team.INBOUND_MAIL, TeamClassifier.classify("", List.of("AGENT_INBOUND_MAIL")));
        assertEquals(TeamClassifier.Team.OUTBOUND, TeamClassifier.classify("OUTBOUND", List.of("AGENT_INBOUND")));
        assertEquals(TeamClassifier.Team.OTHER, TeamClassifier.classify(null, List.of("SUPERVISEUR_QA")));
    }
}
