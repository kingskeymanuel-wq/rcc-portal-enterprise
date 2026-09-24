package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AtmStatusServiceTest {

    @Test
    void statusesAreNormalized() {
        assertEquals("EN_SERVICE", AtmStatusService.normalizeStatus("en service"));
        assertEquals("SANS_BILLETS", AtmStatusService.normalizeStatus("Sans billets"));
        assertEquals("HORS_SERVICE", AtmStatusService.normalizeStatus("HS"));
        assertThrows(ApiException.class, () -> AtmStatusService.normalizeStatus("peut-être"));
    }

    @Test
    void savesCurrentStateOncePerAgencyAndValidatesCounts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("UPDATE"), any(Object[].class))).thenReturn(0);
        AtmStatusService svc = new AtmStatusService(jdbc);
        var saved = svc.save("ci", "k27", "Agence Yop Niangon (K27)",
                new AtmStatusService.AtmRequest("partiel", List.of("retrait", "depot", "inconnu"), 2, 1, null), "Awa (agence K27)");
        assertEquals("PARTIEL", saved.status());
        assertEquals(List.of("RETRAIT", "DEPOT"), saved.services()); // service inconnu ignoré
        assertEquals("K27", saved.agencyCode());
        verify(jdbc).update(startsWith("INSERT"), any(Object[].class)); // absent : créé
        assertThrows(ApiException.class, () -> svc.save("CI", "K27", "x",
                new AtmStatusService.AtmRequest("EN_SERVICE", List.of(), 2, 3, null), "a")); // 3 en service sur 2
    }
}
