package com.ecobank.rccportal;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class GenerateHashTest {

    @Test
    void genererHash() {
        var encoder = new BCryptPasswordEncoder(12);
        String hash = encoder.encode("EcobankDemo2026!");        System.out.println(">>> HASH = " + hash);
        System.out.println(">>> Longueur = " + hash.length());
    }
}