package com.olea.dowsure.coordinator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * CLI entry point, with the SAME argument surface as the Python coordinator's
 * argparse: {@code --olea-url} (required), {@code --enclave-cid} (default 16),
 * {@code --enclave-port} (default 5005), {@code --raw-payload-file} (required),
 * {@code --tls-proof-file} (required), {@code --dowsure-private-key-file}
 * (required), {@code --eif-digest} (required). ({@code requestId}/{@code evidenceId}
 * are generated, not args.)
 *
 * <p>Unknown or missing required args print usage to stderr and exit non-zero.
 * The result map is printed to stdout as indent-2 JSON. No tokens, keys, raw
 * payloads, PII, or URLs are logged.
 */
public final class CoordinatorMain {
    private static final String USAGE = String.join(System.lineSeparator(),
            "usage: coordinator --olea-url URL --raw-payload-file FILE --raw-response-b64-file FILE",
            "                   --tls-proof-file FILE --dowsure-private-key-file FILE --eif-digest DIGEST",
            "                   [--enclave-cid CID] [--enclave-port PORT]");

    private CoordinatorMain() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (ArgumentException error) {
            System.err.println(error.getMessage());
            System.err.println(USAGE);
            System.exit(2);
        } catch (Exception error) {
            System.err.println("coordinator failed: " + error.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        Map<String, String> parsed = parse(args);

        String oleaUrl = required(parsed, "--olea-url");
        int enclaveCid = intOr(parsed, "--enclave-cid", 16);
        int enclavePort = intOr(parsed, "--enclave-port", 5005);
        Path rawPayloadFile = Path.of(required(parsed, "--raw-payload-file"));
        Path rawResponseB64File = Path.of(required(parsed, "--raw-response-b64-file"));
        Path tlsProofFile = Path.of(required(parsed, "--tls-proof-file"));
        Path dowsurePrivateKeyFile = Path.of(required(parsed, "--dowsure-private-key-file"));
        String eifDigest = required(parsed, "--eif-digest");

        ObjectMapper mapper = new ObjectMapper();
        Object rawPayload = readJson(mapper, rawPayloadFile);
        String rawResponseB64 = Files.readString(rawResponseB64File, StandardCharsets.UTF_8).strip();
        Object tlsProof = readJson(mapper, tlsProofFile);

        Coordinator coordinator = new Coordinator(
                new HttpOleaClient(mapper),
                new VsockEnclaveClient(new AfVsockTransport(), mapper),
                new Signer());

        Map<String, Object> result = coordinator.run(
                oleaUrl, enclaveCid, enclavePort, rawPayload, rawResponseB64, tlsProof, dowsurePrivateKeyFile, eifDigest);

        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }

    private static Object readJson(ObjectMapper mapper, Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        return mapper.readValue(content, new TypeReference<Object>() {
        });
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> parsed = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new ArgumentException("unexpected argument: " + arg);
            }
            if (!KNOWN.contains(arg)) {
                throw new ArgumentException("unknown argument: " + arg);
            }
            if (i + 1 >= args.length) {
                throw new ArgumentException("missing value for " + arg);
            }
            parsed.put(arg, args[++i]);
        }
        return parsed;
    }

    private static String required(Map<String, String> parsed, String key) {
        String value = parsed.get(key);
        if (value == null) {
            throw new ArgumentException("missing required argument: " + key);
        }
        return value;
    }

    private static int intOr(Map<String, String> parsed, String key, int fallback) {
        String value = parsed.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new ArgumentException("invalid integer for " + key + ": " + value);
        }
    }

    private static final java.util.Set<String> KNOWN = java.util.Set.of(
            "--olea-url", "--enclave-cid", "--enclave-port", "--raw-payload-file",
            "--raw-response-b64-file", "--tls-proof-file", "--dowsure-private-key-file", "--eif-digest");

    /** Signals a bad CLI invocation (usage + non-zero exit). */
    static final class ArgumentException extends RuntimeException {
        ArgumentException(String message) {
            super(message);
        }
    }
}
