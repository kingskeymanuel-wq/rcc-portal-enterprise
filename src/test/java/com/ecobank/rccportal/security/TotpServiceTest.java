//package com.ecobank.rccportal.security;
//
//import org.junit.jupiter.api.Test;
//
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * Vérifie TotpService contre les vecteurs de test officiels RFC 6238 (Appendix B, SHA1) —
// * garantit l'interopérabilité réelle avec Microsoft/Google Authenticator, pas seulement
// * une cohérence interne (generate -> verify sur soi-même prouverait un aller-retour
// * correct mais pas la conformité à l'algorithme standard).
// */
//class TotpServiceTest {
//
//    // Base32 de la chaîne ASCII "12345678901234567890" utilisée par le secret de test RFC 6238 SHA1.
//    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
//
//    private final TotpService totp = new TotpService();
//
//    @Test
//    void matchesOfficialRfc6238TestVectors() {
//        assertEquals("287082", totp.currentCode(RFC_SECRET, 59L));
//        assertEquals("081804", totp.currentCode(RFC_SECRET, 1111111109L));
//        assertEquals("050471", totp.currentCode(RFC_SECRET, 1111111111L));
//        assertEquals("005924", totp.currentCode(RFC_SECRET, 1234567890L));
//        assertEquals("279037", totp.currentCode(RFC_SECRET, 2000000000L));
//        assertEquals("353130", totp.currentCode(RFC_SECRET, 20000000000L));
//    }
//
//    @Test
//    void verifyAcceptsTheCorrectCodeAtTheExactTime() {
//        assertTrue(totp.verify(RFC_SECRET, "287082", 59L));
//    }
//
//    @Test
//    void verifyRejectsAWrongCode() {
//        assertFalse(totp.verify(RFC_SECRET, "000000", 59L));
//    }
//
//    @Test
//    void verifyToleratesOneStepOfClockDrift() {
//        // 59 -> step 1 ; 59+30=89 -> step 2 ; le code du step 1 doit encore passer a step 2 (drift +1)
//        assertTrue(totp.verify(RFC_SECRET, "287082", 59L + 30L));
//        // mais pas a step 3 (drift +2, hors tolerance)
//        assertFalse(totp.verify(RFC_SECRET, "287082", 59L + 60L));
//    }
//
//    @Test
//    void verifyRejectsNonNumericOrWrongLengthInput() {
//        assertFalse(totp.verify(RFC_SECRET, "12345", 59L));      // trop court
//        assertFalse(totp.verify(RFC_SECRET, "abcdef", 59L));     // non numerique
//        assertFalse(totp.verify(RFC_SECRET, null, 59L));
//        assertFalse(totp.verify(null, "287082", 59L));
//    }
//
//    @Test
//    void generateSecretProducesAUsableRoundTrippableBase32Secret() {
//        String secret = totp.generateSecret();
//        assertNotNull(secret);
//        assertTrue(secret.matches("[A-Z2-7]+"), "doit etre un Base32 valide (RFC 4648)");
//        long now = System.currentTimeMillis() / 1000L;
//        String code = totp.currentCode(secret, now);
//        assertTrue(totp.verify(secret, code, now), "un code fraichement genere doit passer sa propre verification");
//    }
//
//    @Test
//    void buildOtpAuthUriIncludesTheSecretAndIssuer() {
//        String uri = totp.buildOtpAuthUri(RFC_SECRET, "kone.aissatou", "Ecobank RCC");
//        assertTrue(uri.startsWith("otpauth://totp/"));
//        assertTrue(uri.contains("secret=" + RFC_SECRET));
//        assertTrue(uri.contains("issuer=Ecobank%20RCC"));
//        assertTrue(uri.contains("kone.aissatou"));
//    }
//}
