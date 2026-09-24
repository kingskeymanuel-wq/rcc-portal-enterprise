package com.ecobank.rccportal.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MonRccCountryTest {
    @Test
    void countryCodesAreNormalized() {
        assertEquals("CI", MonRccService.country2("CIV"));
        assertEquals("CI", MonRccService.country2("ci"));
        assertEquals("CM", MonRccService.country2("CMR"));
        assertNull(MonRccService.country2(" "));
    }
}
