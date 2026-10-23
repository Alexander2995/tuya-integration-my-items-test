package net.myitems.tuyademo;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

@Component
public class TuyaClient {
    private final TuyaConfig config;
    private final CloudTransport transport;
    private final ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private String access = "", refresh = "";
    private Instant renewAt = Instant.EPOCH;
    public TuyaClient(TuyaConfig config, CloudTransport transport) { this.config = config; this.transport = transport; }
    public JsonNode info() { return business("GET", config.infoPath(), ""); }
    public JsonNode specification() { return business("GET", config.devicePath("spec"), ""); }
    public JsonNode status() { return business("GET", config.devicePath("status"), ""); }
    public boolean command(String code, boolean value) {
        boolean v2 = config.family().equals("CLOUD_V2");
        Object payload = v2 ? Map.of("properties", json.writeValueAsString(Map.of(code, value)))
            : Map.of("commands", List.of(Map.of("code", code, "value", value)));
        JsonNode reply = business("POST", config.devicePath("commands"), json.writeValueAsString(payload));
        return v2 || reply.path("result").asBoolean(false);
    }
    private synchronized String token() {
        config.validate();
        if (Instant.now().isBefore(renewAt) && !access.isEmpty()) return access;
        JsonNode response;
        if (refresh.isEmpty()) response = request("GET", "/v1.0/token?grant_type=1", "", "");
        else {
            try { response = request("GET", "/v1.0/token/" + URLEncoder.encode(refresh, StandardCharsets.UTF_8), "", ""); }
            catch (DemoException e) {
                if (!Set.of("TUYA_1010", "TUYA_1011", "TUYA_1012").contains(e.code)) throw e;
                response = request("GET", "/v1.0/token?grant_type=1", "", "");
            }
        }
        JsonNode r = response.path("result");
        String next = r.path("access_token").asText("");
        int seconds = r.path("expire_time").asInt(r.path("expire").asInt(0));
        if (next.isBlank() || seconds <= 0) throw new DemoException("TOKEN_FORMAT", "Tuya вернула неполный ответ авторизации.");
        access = next; refresh = r.path("refresh_token").asText("");
        renewAt = Instant.now().plusSeconds(Math.max(1, seconds - Math.min(60, seconds / 2)));
        return access;
    }
    private JsonNode business(String method, String path, String body) {
        String used = token();
        try { return request(method, path, body, used); }
        catch (DemoException e) {
            // Never retry a command: an uncertain response must not duplicate an action.
            if (method.equals("GET") && Set.of("TUYA_1010", "TUYA_1011").contains(e.code)) {
                synchronized (this) { if (access.equals(used)) renewAt = Instant.EPOCH; }
                return request(method, path, body, token());
            }
            throw e;
        }
    }
    private JsonNode request(String method, String path, String body, String token) {
        String time = Long.toString(System.currentTimeMillis()), nonce = UUID.randomUUID().toString();
        String signature = TuyaSigner.sign(config.get("TUYA_CLIENT_ID"), config.get("TUYA_CLIENT_SECRET"), token,
            time, nonce, TuyaSigner.stringToSign(method, path, body));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.base() + path)).timeout(Duration.ofSeconds(10))
            .header("client_id", config.get("TUYA_CLIENT_ID")).header("t", time).header("nonce", nonce)
            .header("sign_method", "HMAC-SHA256").header("sign", signature).header("Content-Type", "application/json");
        if (!token.isEmpty()) b.header("access_token", token);
        b.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            CloudTransport.Reply reply = transport.send(b.build());
            if (reply.status() == 429) throw new DemoException("QUOTA", "Превышена частота запросов или квота Tuya. Отключите автообновление и проверьте Usage.");
            if (reply.status() == 401 || reply.status() == 403) throw new DemoException("PERMISSION", "Tuya отклонила авторизацию. Проверьте ключи, регион и подписки API Services.");
            if (reply.status() < 200 || reply.status() >= 300) throw new DemoException("HTTP", "Облако Tuya вернуло HTTP " + reply.status() + ". Повторите чтение позже.");
            JsonNode r;
            try { r = json.readTree(reply.body()); } catch (RuntimeException e) { throw new DemoException("FORMAT", "Облако вернуло ответ, который не удалось прочитать."); }
            if (r == null || !r.path("success").asBoolean(false)) {
                String code = r == null ? "unknown" : r.path("code").asText("unknown");
                if (!code.matches("[0-9]{1,10}")) code = "unknown";
                throw new DemoException("TUYA_" + code, safeError(code));
            }
            return r;
        } catch (HttpTimeoutException e) { throw new DemoException("TIMEOUT", "Tuya не ответила за 10 секунд. Результат отправленной команды может быть неизвестен; обновите состояние."); }
        catch (java.io.IOException e) { throw new DemoException("NETWORK", "Не удалось связаться с Tuya. Проверьте интернет, DNS и региональный endpoint. Результат отправленной команды может быть неизвестен."); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DemoException("INTERRUPTED", "Запрос прерван; результат команды может быть неизвестен."); }
    }
    private String safeError(String code) {
        String tip = switch (code) {
            case "1004" -> "Проверьте Client ID/Secret и часы Windows: ошибка подписи.";
            case "1010", "1011", "1012" -> "Ошибка токена. Проверьте ключи и регион проекта.";
            case "1106" -> "Нет прав. Проверьте API Services, привязку аккаунта, регион и выбранное семейство API.";
            default -> "Проверьте права/срок подписки, квоту, ID устройства и семейство API в API Explorer.";
        };
        return "Ошибка Tuya (код " + code + "). " + tip;
    }
}
