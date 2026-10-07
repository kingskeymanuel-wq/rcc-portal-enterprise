package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** Saisie d'une cellule dans l'éditeur de tables : conversion vers le type de la colonne SQL Server. */
class AdminDataServiceTest {

    private static AdminDataService.Column col(String type, Integer len, boolean nullable) {
        return new AdminDataService.Column("C", type, len, nullable, true, null);
    }

    @Test
    void convertsToTheColumnType() {
        assertEquals(true, AdminDataService.convert(col("bit", null, true), "Vrai"));
        assertEquals(false, AdminDataService.convert(col("bit", null, true), "False"));
        assertEquals(89, AdminDataService.convert(col("int", null, false), "89"));
        assertEquals(30286L, AdminDataService.convert(col("bigint", null, false), " 30286 "));
        assertEquals(LocalDate.of(2026, 10, 1), AdminDataService.convert(col("date", null, true), "2026-10-01"));
        assertEquals("K01", AdminDataService.convert(col("nvarchar", 3, true), "K01"));
        OffsetDateTime o = (OffsetDateTime) AdminDataService.convert(col("datetimeoffset", null, true), "2026-09-28 15:55:12.1234567 +00:00");
        assertEquals(15, o.getHour());
    }

    @Test
    void emptyMeansNullOnlyWhenAllowed() {
        assertNull(AdminDataService.convert(col("nvarchar", 200, true), ""));
        assertNull(AdminDataService.convert(col("nvarchar", 200, true), "NULL"));
        assertThrows(ApiException.class, () -> AdminDataService.convert(col("bigint", null, false), ""));
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(ApiException.class, () -> AdminDataService.convert(col("nvarchar", 3, true), "Côte d'Ivoire"));
        assertThrows(ApiException.class, () -> AdminDataService.convert(col("int", null, true), "abc"));
        assertThrows(ApiException.class, () -> AdminDataService.convert(col("bit", null, true), "peut-être"));
    }
}
