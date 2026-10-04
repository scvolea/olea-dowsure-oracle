package com.olea.dowsure.coordinator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Loads an EC private key from a PEM file and produces a base64-encoded
 * ECDSA-SHA256 signature, mirroring the Python coordinator's {@code sign}.
 *
 * <p>The private key material is held only in local variables for the duration of
 * the signing call and is NEVER logged or persisted.
 */
public final class Signer {

    /**
     * Signs the given canonical string with the EC private key loaded from
     * {@code privateKeyFile} and returns the base64-encoded signature.
     */
    public String sign(Path privateKeyFile, String value) {
        try {
            PrivateKey privateKey = loadPrivateKey(privateKeyFile);
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(privateKey);
            signature.update(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (IOException error) {
            throw new IllegalStateException("DOWSURE_KEY_READ_FAILED", error);
        } catch (Exception error) {
            throw new IllegalStateException("DOWSURE_SIGNATURE_FAILED", error);
        }
    }

    private static PrivateKey loadPrivateKey(Path privateKeyFile) throws IOException {
        String pem = Files.readString(privateKeyFile, StandardCharsets.UTF_8);
        byte[] der = decodePkcs8(pem);
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception error) {
            throw new IllegalStateException("DOWSURE_KEY_INVALID", error);
        }
    }

    private static byte[] decodePkcs8(String pem) {
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN EC PRIVATE KEY-----", "")
                .replace("-----END EC PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
