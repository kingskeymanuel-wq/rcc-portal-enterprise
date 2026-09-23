package com.ecobank.rccportal.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * HMAC-SHA512 en Base64 — porté directement depuis Closed Loop Service
 * (com.ecobank.closedloopservice.security.HmacUtil), même gateway
 * d'authentification SAGED centralisée, même contrat.
 *
 * <p>Le backend normalise le JSON avant vérification (ordre des clés canonique).
 * Le payload doit être sérialisé avec {@code objectMapper.writeValueAsString()}
 * dont l'ordre par défaut correspond à l'ordre de déclaration des champs — voir
 * AuthGatewayClient, qui utilise un TreeMap (clés triées alphabétiquement).</p>
 */
public class HmacUtil {

    private static final String ALGO = "HmacSHA512";

    /**
     * Calcule le HMAC-SHA512 d'un payload et retourne le résultat en Base64.
     *
     * @param data       payload à signer (JSON stringifié)
     * @param hmacSecret clé secrète partagée avec le back
     * @return signature HMAC-SHA512 encodée en Base64 standard
     */
    public static String calculate(String data, String hmacSecret) throws Exception {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("[HMAC] data est requis");
        }
        if (hmacSecret == null || hmacSecret.isEmpty()) {
            throw new IllegalArgumentException("[HMAC] hmacSecret est requis");
        }

        Mac mac = Mac.getInstance(ALGO);
        SecretKeySpec keySpec = new SecretKeySpec(
                hmacSecret.getBytes(StandardCharsets.UTF_8),
                ALGO
        );
        mac.init(keySpec);

        byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

        return Base64.getEncoder().encodeToString(rawHmac);
    }
}
