package com.olea.dowsure.coordinator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip proof that {@link Signer} produces an ECDSA-SHA256 signature over
 * the canonical bytes that verifies against the matching public key.
 */
class SignerTest {

    @Test
    void signsCanonicalBytesWithEcdsaSha256(@TempDir Path tempDir) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();

        Path keyFile = tempDir.resolve("dowsure-key.pem");
        writePkcs8Pem(keyFile, keyPair);

        String canonical = Canonicalizer.canonicalize(java.util.Map.of("requestId", "r1", "nonce", "n1"));
        String signatureB64 = new Signer().sign(keyFile, canonical);

        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(signatureB64)),
                "signature must verify against the matching public key");
    }

    private static void writePkcs8Pem(Path file, KeyPair keyPair) throws Exception {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(keyPair.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(file, pem, StandardCharsets.UTF_8);
    }
}
