# Step 5 — Transform (pass-through) + hashing

## Who
**Nitro Enclave** (`EnclaveService.acquire()`, still inside the same call)

## What happens
The enclave takes the raw source response from Step 4 and produces two hashes that
will be bound into the attestation document. The "transform" is a pure pass-through
per the current directive — `transformed = rawPayload` with zero logic.

## The pass-through transform

```java
// EnclaveService.java — PURE PASS-THROUGH: the raw payload is echoed as-is, no logic.
Map<String, Object> rawPayload = parseBody(rawResponseBytes);
Map<String, Object> transformed = rawPayload;  // identity
```

**Why pass-through?** The user directive was "no transforms, just pass in data as is."
The architecture supports arbitrary transforms (the `transformed` variable could be
the output of any function), but for this PoC/preprod the raw data is what Olea needs.
This means `rawHash` and `transformedHash` will always be different because they hash
different things (wire bytes vs. canonical JSON), but the *data content* is identical.

## The two hashes

| Hash | Input | How computed | Purpose |
|---|---|---|---|
| `rawHash` | Exact wire bytes from Step 4 (`rawResponseBytes`) | `SHA-256(rawResponseBytes)` — hashes status line + headers + body verbatim | Proves the raw HTTP response hasn't been altered since the enclave received it. The verifier checks this against `rawPayloadDigest` by re-hashing the base64-decoded `rawResponseB64`. |
| `transformedHash` | Canonical JSON of `transformed` | `SHA-256(canonicalize(transformed))` — RFC 8785-style sorted-key JSON | Proves the transformed output (what Olea actually consumes) derives from the raw data. Because transform is pass-through, this is `SHA-256(sorted-key-JSON(rawPayload))`. |

```java
// EnclaveService.java
String rawHash = sha256Bytes(rawResponseBytes);           // SHA-256 of wire bytes
String transformedHash = sha256(canonical(transformed));  // SHA-256 of canonical JSON
```

## Canonicalization

`EnclaveService.canonical()` — TreeMap key-sort, then `ObjectMapper.writeValueAsString()`:
```java
String canonical(Object value) {
    if (value instanceof Map<?, ?> map) {
        Map<String, Object> sorted = new TreeMap<>();
        map.forEach((key, item) -> sorted.put(String.valueOf(key), item));
        return mapper.writeValueAsString(sorted);
    }
    return mapper.writeValueAsString(value);
}
```

The verifier uses the equivalent JavaScript (`verification-contract.js:canonicalize()`):
```javascript
if (typeof value === 'object')
  return `{${Object.keys(value).sort().map(k => `${JSON.stringify(k)}:${canonicalize(value[k])}`).join(',')}}`;
```

Both produce `RFC8785-PoC`-tagged output. The enclave and verifier must produce
**byte-identical** canonical forms or the hash comparison fails.

## Example (simplified)

Raw wire bytes (from Step 4):
```
HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{"z":1,"a":2}
```

- `rawHash` = `SHA-256("HTTP/1.1 200 OK\r\n...")` → hex digest of the full wire bytes
- `rawPayload` = `{"z":1,"a":2}` (parsed JSON body)
- `transformed` = `{"z":1,"a":2}` (same, pass-through)
- `transformedHash` = `SHA-256('{"a":2,"z":1}')` → note keys sorted

## Why two separate hashes

- `rawHash` proves **what the source API actually sent** at the wire level. Even headers
  and the status line are included. This is the strongest integrity binding.
- `transformedHash` proves **what Olea will consume** after any business transform.
  Today they're from the same data (pass-through), but if transforms are added later,
  Olea can verify both the raw source and the derived output independently.

Both hashes go into the attestation `user_data` at Step 6, so the hardware attestation
covers both.

## What can go wrong

| Error | Cause |
|---|---|
| `SOURCE_RESPONSE_INVALID` | `parseBody()` can't find JSON in the response (malformed HTTP, non-JSON body) |
| `NON_CANONICAL_VALUE` | Canonicalization fails (shouldn't happen for valid JSON Maps) |

## Outputs → Step 6
- `rawHash` (hex string)
- `transformedHash` (hex string)
- `rawPayload` (Map)
- `transformed` (Map, same object as rawPayload)
- `rawResponseB64` (base64 of wire bytes)
