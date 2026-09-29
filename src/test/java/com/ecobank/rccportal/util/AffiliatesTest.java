package com.ecobank.rccportal.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Code d'agence des comptes importés : « K01 » = Côte d'Ivoire. */
class AffiliatesTest {

    @Test
    void k01IsCoteDIvoire() {
        assertEquals("CI", Affiliates.countryOf("K01"));
        assertEquals("CI", Affiliates.countryOf(" k01 "));
        assertEquals("CI", Affiliates.countryOf("CI"));
        assertEquals("CI", Affiliates.countryOf(null));
        assertEquals("TG", Affiliates.countryOf("TG"));
        assertTrue(Affiliates.sameCountry("CI", "K01"));
        assertFalse(Affiliates.sameCountry("TG", "K01"));
        assertEquals("CI", com.ecobank.rccportal.service.HrOrganizationService.countryOf("K01"));
    }
}
