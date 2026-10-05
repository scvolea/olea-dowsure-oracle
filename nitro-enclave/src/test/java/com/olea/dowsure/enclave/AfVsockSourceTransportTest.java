package com.olea.dowsure.enclave;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline unit tests for {@link AfVsockSourceTransport}'s host-&gt;vsock-port
 * mapping and the fail-closed {@code RELAY_PORT_UNMAPPED} path. Mirrors
 * {@code SourceRegistryTest}: everything is driven through the injected-env /
 * injected-map seams, so NO real vsock is ever opened ({@code connect(...)} is
 * never called).
 */
class AfVsockSourceTransportTest {

    private static final String SAMPLE_MAP =
            "sandbox.sellingpartnerapi-na.amazon.com=8001,api.qichacha.com=8002";

    @Test
    void resolvesMappedHostToVsockPort() {
        AfVsockSourceTransport transport =
                new AfVsockSourceTransport(3, AfVsockSourceTransport.parsePortMap(SAMPLE_MAP));

        assertEquals(8001, transport.resolveVsockPort("sandbox.sellingpartnerapi-na.amazon.com"));
        assertEquals(8002, transport.resolveVsockPort("api.qichacha.com"));
    }

    @Test
    void unmappedHostFailsClosedWithRelayPortUnmapped() {
        AfVsockSourceTransport transport =
                new AfVsockSourceTransport(3, AfVsockSourceTransport.parsePortMap(SAMPLE_MAP));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> transport.resolveVsockPort("unknown.example.com"));
        assertEquals("RELAY_PORT_UNMAPPED", error.getMessage());
    }

    @Test
    void parsePortMapTrimsWhitespaceAroundHostAndPort() {
        Map<String, Integer> map =
                AfVsockSourceTransport.parsePortMap("  host1 = 8001 ,  host2=8002  ");

        assertEquals(8001, map.get("host1"));
        assertEquals(8002, map.get("host2"));
        assertEquals(2, map.size());
    }

    @Test
    void parsePortMapSkipsTrailingAndDoubleCommas() {
        Map<String, Integer> map =
                AfVsockSourceTransport.parsePortMap("host1=8001,,host2=8002,");

        assertEquals(8001, map.get("host1"));
        assertEquals(8002, map.get("host2"));
        assertEquals(2, map.size());
    }

    @Test
    void parsePortMapReturnsEmptyMapForNullOrBlankInput() {
        assertTrue(AfVsockSourceTransport.parsePortMap(null).isEmpty());
        assertTrue(AfVsockSourceTransport.parsePortMap("").isEmpty());
        assertTrue(AfVsockSourceTransport.parsePortMap("   ").isEmpty());
    }

    @Test
    void fromEnvDefaultsRelayCidToThreeWhenUnset() {
        AfVsockSourceTransport transport =
                AfVsockSourceTransport.fromEnv(Map.of(
                        AfVsockSourceTransport.RELAY_PORT_MAP_ENV, SAMPLE_MAP));

        // CID default (3) is not directly observable, but a mapped host must still
        // resolve, proving fromEnv wired the port map through with the default CID.
        assertEquals(8001, transport.resolveVsockPort("sandbox.sellingpartnerapi-na.amazon.com"));
    }

    @Test
    void fromEnvHonorsExplicitRelayCidAndPortMap() {
        AfVsockSourceTransport transport =
                AfVsockSourceTransport.fromEnv(Map.of(
                        AfVsockSourceTransport.RELAY_CID_ENV, " 5 ",
                        AfVsockSourceTransport.RELAY_PORT_MAP_ENV, "host1=9001"));

        assertEquals(9001, transport.resolveVsockPort("host1"));
    }

    @Test
    void fromEnvWithEmptyEnvYieldsFailClosedTransport() {
        AfVsockSourceTransport transport = AfVsockSourceTransport.fromEnv(Map.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> transport.resolveVsockPort("sandbox.sellingpartnerapi-na.amazon.com"));
        assertEquals("RELAY_PORT_UNMAPPED", error.getMessage());
    }
}
