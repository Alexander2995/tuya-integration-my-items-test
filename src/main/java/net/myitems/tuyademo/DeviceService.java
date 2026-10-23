package net.myitems.tuyademo;

import java.time.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class DeviceService {
    public record Snapshot(String name, Boolean online, String receivedAt, DeviceNormalizer.Reading reading,
                           List<Map<String, Object>> rawStatus, List<Map<String, Object>> rawSpecification, String source) {}
    public record Problem(String code, String message) {}
    public record State(boolean configured, String family, String cloud, boolean stale, Snapshot snapshot, Problem error) {}
    public record CommandResult(boolean accepted, boolean confirmed, String message, State state) {}
    private final TuyaClient client;
    private final TuyaConfig config;
    private final DeviceNormalizer normalizer = new DeviceNormalizer();
    private final ReentrantLock operation = new ReentrantLock();
    private volatile Snapshot last;
    private volatile Problem error;
    private volatile String cloud = "not_requested";
    private List<DeviceNormalizer.Definition> definitions;
    private Instant specExpires = Instant.EPOCH;
    private long lastReadNanos;
    private final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();
    private final Map<String, JsonNode> properties = new LinkedHashMap<>();
    private final LinkedHashSet<String> eventIds = new LinkedHashSet<>();
    private long onlineTime;
    public DeviceService(TuyaClient client, TuyaConfig config) { this.client = client; this.config = config; }
    public State state() { return new State(config.missing().isEmpty(), config.family(), cloud, error != null && last != null, last, error); }
    public State refresh() {
        lock();
        try {
            // Page reloads and several tabs reuse one recent reading.
            if (last != null && error == null && System.nanoTime() - lastReadNanos < 3_000_000_000L) return state();
            try { read(false); } catch (DemoException e) { failed(e); }
            return state();
        } finally { operation.unlock(); }
    }
    public State reconcile() {
        operation.lock();
        try { try { read(false); } catch (DemoException e) { failed(e); } return state(); }
        finally { operation.unlock(); }
    }
    public List<String> applyEvent(JsonNode payload, int protocol) {
        operation.lock();
        try {
            if (definitions == null || last == null) return List.of();
            var event = DeviceEvent.parse(payload, protocol, definitions);
            if (!config.get("TUYA_DEVICE_ID").equals(event.deviceId())) return List.of();
            if (!event.dataId().isBlank() && eventIds.contains(event.dataId())) return List.of();
            List<String> applied = new ArrayList<>();
            for (JsonNode row : event.properties()) {
                String code = row.path("code").asText();
                JsonNode prior = properties.get(code);
                long time = row.path("time").asLong();
                if (prior == null || time > prior.path("time").asLong(0)) {
                    properties.put(code, row); applied.add(code);
                }
            }
            Boolean online = last.online();
            if (event.online() != null && event.onlineTime() > onlineTime && event.onlineTime() >= 946684800000L && event.onlineTime() <= 4102444800000L) {
                online = event.online(); onlineTime = event.onlineTime(); applied.add("online");
            }
            if (!event.dataId().isBlank()) {
                eventIds.add(event.dataId());
                if (eventIds.size() > 1024) eventIds.remove(eventIds.iterator().next());
            }
            if (applied.isEmpty()) return List.of();
            JsonNode merged = propertyArray();
            last = new Snapshot(last.name(), online, Instant.now().toString(), normalizer.normalize(definitions, merged, mapping()),
                Diagnostics.status(merged), last.rawSpecification(), "message_queue");
            return applied;
        } finally { operation.unlock(); }
    }
    public CommandResult command(boolean target) {
        lock();
        boolean accepted = false;
        try {
            read(false);
            if (Boolean.FALSE.equals(last.online())) return new CommandResult(false, false, "Розетка offline. Команда не отправлена.", state());
            var r = last.reading();
            if (r.relayCode() == null || !r.writable() || r.relay() == null)
                return new CommandResult(false, false, "Для команды нужен однозначный доступный для записи Boolean-код реле и его показание. Проверьте диагностику и TUYA_RELAY_CODE.", state());
            String beforeTime = r.relayReportedAt();
            boolean alreadyTarget = Objects.equals(r.relay(), target);
            accepted = client.command(r.relayCode(), target);
            cloud = "connected";
            if (!accepted) return new CommandResult(false, false, "Облако не приняло команду.", state());
            long deadline = System.nanoTime() + 8_000_000_000L;
            for (int attempt = 0; attempt < 3 && System.nanoTime() < deadline; attempt++) {
                if (attempt > 0) Thread.sleep(1500);
                read(true);
                var observed = last.reading();
                boolean matching = Objects.equals(observed.relay(), target) && !Boolean.FALSE.equals(last.online());
                boolean freshReport = beforeTime == null || (observed.relayReportedAt() != null && observed.relayReportedAt().compareTo(beforeTime) > 0);
                if (matching && (freshReport || alreadyTarget)) {
                    String message = alreadyTarget ? "Команда принята. Требуемое состояние уже было установлено и снова получено из облака."
                        : "Команда принята. Требуемое состояние подтверждено новым чтением показания реле" +
                            (observed.relayReportedAt() == null ? "; время отчёта API не предоставляет." : ".");
                    return new CommandResult(true, true, message, state());
                }
            }
            return new CommandResult(true, false, "Команда принята облаком. За ограниченное время свежее подтверждение реле не получено. Нажмите «Обновить» позже.", state());
        } catch (DemoException e) {
            failed(e);
            return new CommandResult(accepted, false, (accepted ? "Команда принята, но подтвердить состояние не удалось. " : "") + e.getMessage(), state());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(accepted, false, "Ожидание прервано. Обновите состояние; команда повторно не отправляется.", state());
        } finally { operation.unlock(); }
    }
    private void lock() { if (!operation.tryLock()) throw new DemoException("BUSY", "Уже выполняется запрос. Дождитесь его завершения."); }
    private void read(boolean statusOnly) {
        config.validate();
        if (definitions == null || Instant.now().isAfter(specExpires)) {
            definitions = normalizer.definitions(client.specification());
            specExpires = Instant.now().plusSeconds(900);
        }
        JsonNode info = statusOnly && last != null ? null : client.info().path("result");
        if (info != null && !info.isObject()) throw new DemoException("SCHEMA", "Не удалось прочитать информацию устройства.");
        JsonNode statuses = normalizer.statuses(client.status());
        for (JsonNode row : statuses) {
            String code = row.path("code").asText();
            JsonNode prior = properties.get(code);
            if (prior == null || row.path("time").asLong(0) >= prior.path("time").asLong(0)) properties.put(code, row);
        }
        statuses = propertyArray();
        var reading = normalizer.normalize(definitions, statuses, mapping());
        Boolean online = last == null ? null : last.online();
        String name = last == null ? "Нет данных" : last.name();
        if (info != null) {
            JsonNode flag = info.path(config.family().equals("CLOUD_V2") ? "is_online" : "online");
            online = flag.isBoolean() ? flag.asBoolean() : null;
            onlineTime = System.currentTimeMillis();
            name = info.path("name").asText("Нет данных");
        }
        last = new Snapshot(name, online, Instant.now().toString(), reading, Diagnostics.status(statuses), Diagnostics.specification(definitions), "rest");
        cloud = "connected"; error = null; lastReadNanos = System.nanoTime();
    }
    private Map<String, String> mapping() {
        Map<String, String> mapping = new HashMap<>();
        for (String key : List.of("RELAY", "POWER", "VOLTAGE", "CURRENT", "ENERGY")) mapping.put(key, config.get("TUYA_" + key + "_CODE"));
        return mapping;
    }
    private JsonNode propertyArray() {
        var array = json.createArrayNode(); properties.values().forEach(array::add); return array;
    }
    private void failed(DemoException e) { cloud = e.code.equals("CONFIG") ? "not_configured" : "error"; error = new Problem(e.code, e.getMessage()); }
}
