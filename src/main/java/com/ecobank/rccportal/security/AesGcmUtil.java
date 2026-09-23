package com.ecobank.rccportal.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Chiffrement AES-256-GCM avec dérivation de clé PBKDF2-SHA256 — porté
 * directement depuis Closed Loop Service (com.ecobank.closedloopservice
 * .security.AesGcmUtil), même gateway d'authentification SAGED centralisée.
 *
 * <p>Format du payload chiffré : {@code base64(ciphertext+tag):base64(iv):base64(salt)}</p>
 *
 * <ul>
 *   <li>PBKDF2 — 100 000 itérations, SHA-256, clé 256 bits</li>
 *   <li>AES-GCM — IV 12 bytes, tag 128 bits (16 bytes), salt 16 bytes</li>
 *   <li>Ciphertext et tag concaténés avant encodage Base64</li>
 * </ul>
 */
public class AesGcmUtil {

    private static final int ITERATIONS = 100_000;
    private static final int KEY_BITS = 256;
    private static final int TAG_BITS = 128;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;

    private static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA256";
    private static final String AES_ALGO = "AES/GCM/NoPadding";
    private static final String SEP = ":";

    private static final SecureRandom RNG = new SecureRandom();

    /**
     * Chiffre un texte en clair avec AES-256-GCM.
     *
     * @param plaintext texte à chiffrer
     * @param password  mot de passe pour dériver la clé
     * @return {@code base64(ciphertext+tag):base64(iv):base64(salt)}
     */
    public static String encrypt(String plaintext, String password) throws Exception {
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] iv = randomBytes(IV_BYTES);

        SecretKey key = deriveKey(password, salt);

        Cipher cipher = Cipher.getInstance(AES_ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));

        byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        return encode(ciphertextWithTag) + SEP + encode(iv) + SEP + encode(salt);
    }

    /**
     * Déchiffre un payload AES-256-GCM.
     *
     * @param encryptedData {@code base64(ciphertext+tag):base64(iv):base64(salt)}
     * @param password      mot de passe pour dériver la clé
     * @return texte en clair
     */
    public static String decrypt(String encryptedData, String password) throws Exception {
        if (encryptedData == null || !encryptedData.contains(SEP)) {
            throw new IllegalArgumentException("[AES-GCM] Format invalide");
        }

        String[] parts = encryptedData.split(SEP);
        if (parts.length != 3) {
            throw new IllegalArgumentException("[AES-GCM] Format invalide — attendu 3 segments");
        }

        byte[] ciphertextWithTag = decode(parts[0]);
        byte[] iv = decode(parts[1]);
        byte[] salt = decode(parts[2]);

        SecretKey key = deriveKey(password, salt);

        Cipher cipher = Cipher.getInstance(AES_ALGO);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));

        byte[] plaintext = cipher.doFinal(ciphertextWithTag);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGO);
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        RNG.nextBytes(bytes);
        return bytes;
    }

    private static String encode(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private static byte[] decode(String b64) {
        return Base64.getDecoder().decode(b64);
    }
}
