package net.myitems.tuyademo;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;

class QueueTests {
    final ObjectMapper json = new ObjectMapper();
    final String secret = "0123456789abcdef0123456789abcdef";
    @Test void aesGcmUsesTuyaNonceCiphertextTagFramingAndRejectsTampering() throws Exception {
        String plain = "{\"status\":[{\"code\":\"cur_power\",\"value\":123}]}";
        byte[] nonce = new byte[12]; Arrays.fill(nonce, (byte)7);
        Cipher encryption = Cipher.getInstance("AES/GCM/NoPadding");
        encryption.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret.substring(8,24).getBytes(StandardCharsets.UTF_8), "AES"), new GCMParameterSpec(128, nonce));
        byte[] cipher = encryption.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] frame = new byte[nonce.length + cipher.length]; System.arraycopy(nonce,0,frame,0,12); System.arraycopy(cipher,0,frame,12,cipher.length);
        assertEquals(plain, TuyaQueueCrypto.decrypt(Base64.getEncoder().encodeToString(frame), "aes_gcm", secret));
        frame[frame.length-1] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> TuyaQueueCrypto.decrypt(Base64.getEncoder().encodeToString(frame), "aes_gcm", secret));
        assertThrows(GeneralSecurityException.class, () -> TuyaQueueCrypto.decrypt(Base64.getEncoder().encodeToString(frame), "aes_ecb", secret));
        assertThrows(GeneralSecurityException.class, () -> TuyaQueueCrypto.decrypt("invalid-base64", "aes_gcm", secret));
        assertThrows(GeneralSecurityException.class, () -> TuyaQueueCrypto.decrypt("AA==", "aes_gcm", secret));
    }
    @Test void authPluginUsesCommandAuthenticationAndDoesNotEmbedSecret() throws Exception {
        var authentication = new TuyaQueueAuthentication("test-client", secret);
        assertEquals("auth1", authentication.getAuthMethodName());
        assertTrue(authentication.getAuthData().hasDataFromCommand());
        assertFalse(authentication.getAuthData().hasDataForHttp());
        JsonNode command = json.readTree(authentication.getAuthData().getCommandData());
        assertEquals("test-client", command.path("username").asText());
        assertEquals(16, command.path("password").asText().length());
        assertFalse(authentication.getAuthData().getCommandData().contains(secret));
    }
    record Fixture(DeviceService service, TuyaTests.FakeCloud cloud) {}
    Fixture fixture() {
        TuyaTests helpers = new TuyaTests();
        var cfg = new TuyaConfig(helpers.env()); var cloud = helpers.new FakeCloud();
        var service = new DeviceService(new TuyaClient(cfg, cloud), cfg); service.refresh();
        return new Fixture(service, cloud);
    }
    JsonNode event(String id, String device, String code, Object value, long time) {
        return json.valueToTree(Map.of("dataId", id, "devId", device, "status", List.of(Map.of("code",code,"value",value,"t",time))));
    }
    @Test void partialEventUpdatesPowerWithoutRestOrCommandsAndPreservesOtherValues() {
        var f = fixture(); int calls = f.cloud.requests.size();
        List<String> changed = f.service.applyEvent(event("one", "test-device", "cur_power", 235, 1792690080000L), 4);
        assertEquals(List.of("cur_power"), changed);
        assertEquals("23.5", f.service.state().snapshot().reading().measurements().get("power").value());
        assertEquals(false, f.service.state().snapshot().reading().relay());
        assertEquals("message_queue", f.service.state().snapshot().source());
        assertEquals(calls, f.cloud.requests.size()); assertEquals(0, f.cloud.commands);
    }
    @Test void duplicateOldAndForeignDeviceEventsCannotOverwriteNewerReports() {
        var f = fixture();
        f.service.applyEvent(event("same", "test-device", "cur_power", 200, 1792690080100L), 4);
        assertTrue(f.service.applyEvent(event("same", "test-device", "cur_power", 999, 1792690080200L), 4).isEmpty());
        assertTrue(f.service.applyEvent(event("old", "test-device", "cur_power", 888, 1792690080000L), 4).isEmpty());
        assertTrue(f.service.applyEvent(event("foreign", "other-device", "cur_power", 777, 1792690081000L), 4).isEmpty());
        assertEquals("20", f.service.state().snapshot().reading().measurements().get("power").value());
    }
    @Test void iotCoreNumericStringsAreConvertedByMetadataAndUnknownCodesAreIgnored() {
        var f = fixture();
        JsonNode event = json.valueToTree(Map.of("bizCode", "devicePropertyMessage", "bizData", Map.of("devId", "test-device", "dataId", "core-one", "properties", List.of(
            Map.of("code", "cur_power", "value", "321", "time", 1792690080000L),
            Map.of("code", "switch", "value", "true", "time", 1792690080000L),
            Map.of("code", "unknown_dp", "value", "123", "time", 1792690080000L)))));
        assertEquals(List.of("cur_power", "switch"), f.service.applyEvent(event, 1000));
        assertEquals("32.1", f.service.state().snapshot().reading().measurements().get("power").value());
        assertEquals(true, f.service.state().snapshot().reading().relay());
    }
    @Test void missingPropertyTimeDoesNotUseEnvelopeTimestamp() {
        var f = fixture();
        var event = json.readTree("{\"devId\":\"test-device\",\"ts\":1792690080000,\"status\":[{\"code\":\"cur_power\",\"value\":999}]}");
        assertTrue(f.service.applyEvent(event, 4).isEmpty());
        assertEquals("12.3", f.service.state().snapshot().reading().measurements().get("power").value());
    }
    @Test void onlineOfflineEventsAreOrderedAndDoNotTurnRelayOrSendCommands() {
        var f = fixture(); long later = System.currentTimeMillis()+10000;
        var off = json.valueToTree(Map.of("bizCode", "deviceOffline", "bizData", Map.of("devId", "test-device", "time",later)));
        assertEquals(List.of("online"), f.service.applyEvent(off,1001)); assertEquals(false,f.service.state().snapshot().online());
        var old = json.valueToTree(Map.of("bizCode", "online", "devId", "test-device", "bizData", Map.of("time",later-1)));
        assertTrue(f.service.applyEvent(old,20).isEmpty()); assertEquals(false,f.service.state().snapshot().online());
        assertEquals(false,f.service.state().snapshot().reading().relay()); assertEquals(0,f.cloud.commands);
    }
    @Test void localStreamStatusDoesNotExposeKeysOrDeviceIdentifiers() {
        var helpers = new TuyaTests(); var monitor = new QueueMonitor(new TuyaConfig(helpers.env()));
        String serialized = json.writeValueAsString(monitor.status());
        assertFalse(serialized.contains("test-client")); assertFalse(serialized.contains("test-secret")); assertFalse(serialized.contains("test-device"));
    }
}
