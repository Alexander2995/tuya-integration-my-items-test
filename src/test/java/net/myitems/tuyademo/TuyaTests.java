package net.myitems.tuyademo;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.*;

class TuyaTests {
    final ObjectMapper json = new ObjectMapper();
    final DeviceNormalizer normalizer = new DeviceNormalizer();
    MockEnvironment env() {
        return new MockEnvironment().withProperty("TUYA_CLIENT_ID", "test-client")
            .withProperty("TUYA_CLIENT_SECRET", "test-secret").withProperty("TUYA_DEVICE_ID", "test-device")
            .withProperty("TUYA_ENDPOINT", "https://openapi.tuyaeu.com").withProperty("TUYA_API_FAMILY", "CLOUD_V2");
    }
    JsonNode node(String s) { return json.readTree(s); }
    List<DeviceNormalizer.Definition> defs(String s) { return normalizer.definitions(node(s)); }
    String spec(int scale) {
        return "{\"result\":{\"functions\":[{\"code\":\"switch\",\"type\":\"Boolean\"}],\"status\":[{\"code\":\"switch\",\"type\":\"Boolean\",\"values\":\"{}\"},{\"code\":\"cur_power\",\"type\":\"Integer\",\"values\":{\"unit\":\"W\",\"scale\":" + scale + "}},{\"code\":\"cur_current\",\"type\":\"Integer\",\"values\":{\"unit\":\"mA\",\"scale\":0}}]}}";
    }
    @Test void officialSignatureVectorIncludesNonceBodyAndHeaderLines() {
        String string = "GET\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\narea_id:29a33e8796834b1efa6\ncall_id:8afdb70ab2ed11eb85290242ac130003\n\n/v1.0/token?grant_type=1";
        assertEquals("9E48A3E93B302EEECC803C7241985D0A34EB944F40FB573C7B5C2A82158AF13E", TuyaSigner.sign("1KAD46OrT9HafiKdsXeg", "4OHBOnWOqaEC1mWXOpVL3yV50s0qGSRC", "", "1588925778000", "5138cc3a9033d69856923fd07b491173", string));
        assertEquals("GET\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n\n/v1.0/token?grant_type=1", TuyaSigner.stringToSign("GET", "/v1.0/token?grant_type=1", ""));
        assertNotEquals(TuyaSigner.stringToSign("POST", "/x", "{}"), TuyaSigner.stringToSign("POST", "/x", "{\"on\":true}"));
    }
    @Test void scaleComesFromDeviceNotCodeAndDecimalsStayExact() {
        JsonNode status = node("[{\"code\":\"cur_power\",\"value\":12345},{\"code\":\"cur_current\",\"value\":1234},{\"code\":\"switch\",\"value\":false}]");
        var first = normalizer.normalize(defs(spec(1)), status, Map.of());
        var second = normalizer.normalize(defs(spec(0)), status, Map.of());
        assertEquals("1234.5", first.measurements().get("power").value());
        assertEquals("12345", second.measurements().get("power").value());
        assertEquals("1.234", first.measurements().get("current").value());
        assertEquals(false, first.relay()); assertTrue(first.writable());
        assertNull(first.measurements().get("voltage").value());
        assertNull(first.measurements().get("energy").value());
    }
    @Test void noScaleOrUnknownUnitOrMissingPowerNeverBecomesZero() {
        var d = defs("{\"result\":{\"status\":[{\"code\":\"cur_power\",\"type\":\"Integer\",\"values\":{\"unit\":\"W\"}}]}}");
        assertNull(normalizer.normalize(d, node("[{\"code\":\"cur_power\",\"value\":0}]"), Map.of()).measurements().get("power").value());
        var unknown = defs(spec(0).replace("\"unit\":\"W\"", "\"unit\":\"mystery\""));
        assertNull(normalizer.normalize(unknown, node("[{\"code\":\"cur_power\",\"value\":90}]"), Map.of()).measurements().get("power").value());
        assertNull(normalizer.normalize(defs(spec(1)), node("[]"), Map.of()).measurements().get("power").value());
    }
    @Test void multipleRelayChannelsRequireExplicitSelection() {
        var d = defs("{\"result\":{\"functions\":[],\"status\":[{\"code\":\"switch_1\",\"type\":\"Boolean\",\"values\":{}},{\"code\":\"switch_2\",\"type\":\"Boolean\",\"values\":{}}]}}");
        var status = node("[{\"code\":\"switch_2\",\"value\":true}]");
        assertNull(normalizer.normalize(d, status, Map.of()).relayCode());
        assertEquals(true, normalizer.normalize(d, status, Map.of("RELAY", "switch_2")).relay());
    }
    @Test void cloudV2ModelAndReportTimeAreParsedWithoutUsingEnvelopeTimestamp() {
        String model = "{\"services\":[{\"code\":\"\",\"properties\":[{\"code\":\"custom_socket\",\"accessMode\":\"rw\",\"typeSpec\":{\"type\":\"bool\"}},{\"code\":\"power_now\",\"accessMode\":\"ro\",\"typeSpec\":{\"type\":\"value\",\"scale\":2,\"unit\":\"kW\"}}]}]}";
        var s = node(json.writeValueAsString(Map.of("result", Map.of("model", model))));
        var state = normalizer.statuses(node("{\"t\":1792690080484,\"result\":{\"properties\":[{\"code\":\"power_now\",\"value\":123,\"time\":1792690080000},{\"code\":\"custom_socket\",\"value\":true}]}}"));
        var r = normalizer.normalize(normalizer.definitions(s), state, Map.of("POWER", "power_now", "RELAY", "custom_socket"));
        assertEquals("1230", r.measurements().get("power").value()); assertTrue(r.writable());
        assertNotNull(r.measurements().get("power").reportedAt()); assertNull(r.relayReportedAt());
        assertThrows(DemoException.class, () -> normalizer.statuses(node("{\"result\":{\"state\":0}}")));
    }
    @Test void diagnosticsExcludePrivateDataAndTextValues() {
        var safe = Diagnostics.status(node("[{\"code\":\"note\",\"value\":\"secret-token\",\"uid\":\"person\",\"local_key\":\"LAN-key\"},{\"code\":\"cur_power\",\"value\":12}]"));
        String out = json.writeValueAsString(safe);
        assertFalse(out.contains("secret-token")); assertFalse(out.contains("LAN-key")); assertFalse(out.contains("person")); assertTrue(out.contains("12"));
    }
    class FakeCloud implements CloudTransport {
        final List<HttpRequest> requests = new ArrayList<>();
        boolean failStatus, rejectOnce, commandMatch = true, online = true;
        int commands, tokens, refreshes;
        String commandBody;
        public Reply send(HttpRequest req) throws IOException {
            requests.add(req);
            String p = req.uri().getPath();
            if (p.equals("/v1.0/token")) { tokens++; return ok("{\"access_token\":\"test-token\",\"refresh_token\":\"test-refresh\",\"expire_time\":7200}"); }
            if (p.equals("/v1.0/token/test-refresh")) { refreshes++; return ok("{\"access_token\":\"new-token\",\"refresh_token\":\"new-refresh\",\"expire_time\":7200}"); }
            if (rejectOnce) { rejectOnce = false; return new Reply(200, "{\"success\":false,\"code\":1010}"); }
            if (p.endsWith("/model")) {
                String m = "{\"services\":[{\"code\":\"\",\"properties\":[{\"code\":\"switch\",\"accessMode\":\"rw\",\"typeSpec\":{\"type\":\"bool\"}},{\"code\":\"cur_power\",\"accessMode\":\"ro\",\"typeSpec\":{\"type\":\"value\",\"unit\":\"W\",\"scale\":1}}]}]}";
                return ok(json.writeValueAsString(Map.of("model", m)));
            }
            if (p.endsWith("/issue")) { commands++; commandBody = body(req); return ok("{}"); }
            if (p.endsWith("/shadow/properties")) {
                if (failStatus) throw new IOException("sensitive-detail-test-token");
                return ok("{\"properties\":[{\"code\":\"switch\",\"value\":" + (commands > 0 && commandMatch) + "},{\"code\":\"cur_power\",\"value\":123}]}");
            }
            return ok("{\"name\":\"Test socket\",\"is_online\":" + online + ",\"local_key\":\"LAN-secret\",\"uid\":\"person\"}");
        }
        Reply ok(String result) { return new Reply(200, "{\"success\":true,\"result\":" + result + "}"); }
    }
    static String body(HttpRequest request) {
        var bytes = new java.io.ByteArrayOutputStream();
        var done = new CompletableFuture<String>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer b) { byte[] chunk = new byte[b.remaining()]; b.get(chunk); bytes.writeBytes(chunk); }
            public void onError(Throwable e) { done.completeExceptionally(e); }
            public void onComplete() { done.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return done.join();
    }
    @Test void tokensAreCachedRefreshedAndRequestsSignedButReadsNeverSendCommands() {
        var fake = new FakeCloud(); var c = new TuyaClient(new TuyaConfig(env()), fake);
        c.info(); c.status(); assertEquals(1, fake.tokens); assertEquals(0, fake.commands);
        var business = fake.requests.get(1);
        assertEquals("test-token", business.headers().firstValue("access_token").orElseThrow());
        String expected = TuyaSigner.sign("test-client", "test-secret", "test-token", business.headers().firstValue("t").orElseThrow(), business.headers().firstValue("nonce").orElseThrow(), TuyaSigner.stringToSign("GET", business.uri().getRawPath(), ""));
        assertEquals(expected, business.headers().firstValue("sign").orElseThrow());
        fake.rejectOnce = true; c.status(); assertEquals(1, fake.refreshes);
    }
    @Test void failedRefreshPreservesLastReadingAndDoesNotExposeUpstreamException() {
        var fake = new FakeCloud(); var cfg = new TuyaConfig(env()); var service = new DeviceService(new TuyaClient(cfg, fake), cfg);
        var first = service.refresh(); assertNotNull(first.snapshot()); assertEquals(0, fake.commands);
        fake.failStatus = true;
        // command preflight is an uncached read; it fails before sending anything.
        var result = service.command(true);
        assertFalse(result.accepted()); assertEquals(0, fake.commands); assertTrue(result.state().stale());
        assertEquals(first.snapshot().receivedAt(), result.state().snapshot().receivedAt());
        assertFalse(json.writeValueAsString(result).contains("test-token"));
        assertFalse(json.writeValueAsString(first).contains("LAN-secret"));
    }
    @Test void acceptedCommandNeedsMatchingReadingAndOfflineDoesNotSend() {
        var fake = new FakeCloud(); var cfg = new TuyaConfig(env()); var service = new DeviceService(new TuyaClient(cfg, fake), cfg);
        var result = service.command(true); assertTrue(result.accepted()); assertTrue(result.confirmed()); assertEquals(1, fake.commands);
        var payload = fake.requests.stream().filter(r -> r.method().equals("POST")).findFirst().orElseThrow();
        assertTrue(payload.uri().getPath().endsWith("/shadow/properties/issue"));
        JsonNode envelope = node(fake.commandBody);
        assertTrue(envelope.path("properties").isString());
        assertTrue(node(envelope.path("properties").asText()).path("switch").asBoolean());
        assertEquals(TuyaSigner.sign("test-client", "test-secret", "test-token", payload.headers().firstValue("t").orElseThrow(), payload.headers().firstValue("nonce").orElseThrow(), TuyaSigner.stringToSign("POST", payload.uri().getRawPath(), fake.commandBody)), payload.headers().firstValue("sign").orElseThrow());
        fake.online = false; var offline = service.command(false); assertFalse(offline.accepted()); assertEquals(1, fake.commands);
        assertEquals("connected", offline.state().cloud()); assertFalse(offline.state().stale());
    }
    @Test void acceptedCommandWithoutConfirmationIsNotReportedAsSuccessfulSwitch() {
        var fake = new FakeCloud(); fake.commandMatch = false; var cfg = new TuyaConfig(env());
        var result = new DeviceService(new TuyaClient(cfg, fake), cfg).command(true);
        assertTrue(result.accepted()); assertFalse(result.confirmed()); assertEquals(1, fake.commands);
    }
    @Test void missingConfigAndUnexpectedEndpointAreRejectedBeforeHttp() {
        var cfg = new TuyaConfig(new MockEnvironment()); assertEquals("CONFIG", assertThrows(DemoException.class, cfg::validate).code);
        var malicious = new TuyaConfig(env().withProperty("TUYA_ENDPOINT", "https://example.com"));
        assertThrows(DemoException.class, malicious::validate);
    }
    @Test void concurrentRefreshAndCommandAreRejectedBeforeSendingAnything() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var fake = new FakeCloud();
        CloudTransport blocking = request -> {
            if (request.uri().getPath().endsWith("/model")) { entered.countDown(); release.await(5, TimeUnit.SECONDS); }
            return fake.send(request);
        };
        var cfg = new TuyaConfig(env()); var service = new DeviceService(new TuyaClient(cfg, blocking), cfg);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<DeviceService.State> pending = pool.submit(service::refresh);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals("BUSY", assertThrows(DemoException.class, () -> service.command(true)).code);
            assertEquals("BUSY", assertThrows(DemoException.class, service::refresh).code);
            release.countDown(); assertNotNull(pending.get(3, TimeUnit.SECONDS).snapshot()); assertEquals(0, fake.commands);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void foreignPageCannotIssueCommandsToLocalService() throws Exception {
        var filter = new LocalRequestFilter();
        var req = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/relay");
        req.setServerName("localhost"); req.addHeader("Host", "localhost:8080");
        req.addHeader("Origin", "https://foreign.example"); req.addHeader("X-Local-Request", "1");
        var res = new org.springframework.mock.web.MockHttpServletResponse(); var calls = new AtomicInteger();
        filter.doFilter(req, res, (a,b) -> calls.incrementAndGet());
        assertEquals(403, res.getStatus()); assertEquals(0, calls.get());
        var valid = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/refresh");
        valid.setServerName("localhost"); valid.addHeader("Host", "localhost:8080"); valid.addHeader("Origin", "http://localhost:8080"); valid.addHeader("X-Local-Request", "1");
        filter.doFilter(valid, new org.springframework.mock.web.MockHttpServletResponse(), (a,b) -> calls.incrementAndGet());
        assertEquals(1, calls.get());
    }
}
