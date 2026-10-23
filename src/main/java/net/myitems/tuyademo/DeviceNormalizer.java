package net.myitems.tuyademo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.*;

/** Pure mapping layer. No HTTP, tokens, Spring or storage. */
public final class DeviceNormalizer {
    private final ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    public record Definition(String code, String type, JsonNode values, boolean writable) {}
    public record Metric(String code, String value, String unit, Integer scale, String reportedAt, String note) {}
    public record Reading(String relayCode, Boolean relay, String relayReportedAt, boolean writable,
                          Map<String, Metric> measurements, List<String> warnings) {}

    public List<Definition> definitions(JsonNode spec) {
        JsonNode result = spec.path("result");
        List<Definition> out = new ArrayList<>();
        if (result.has("model")) {
            JsonNode model = parse(result.path("model"));
            JsonNode services = model.path("services");
            if (!services.isArray()) throw new DemoException("SCHEMA", "Модель Tuya не содержит services. Проверьте семейство API.");
            // Non-default services need service addressing, which this socket demo does not infer.
            for (JsonNode service : services) {
                if (!service.path("code").asText("").isEmpty()) continue;
                for (JsonNode p : service.path("properties")) {
                    JsonNode type = p.path("typeSpec");
                    out.add(new Definition(p.path("code").asText(), type.path("type").asText(), type,
                        Set.of("rw", "wr").contains(p.path("accessMode").asText())));
                }
            }
        } else {
            if (!result.path("status").isArray()) throw new DemoException("SCHEMA", "В спецификации нет массива status. Проверьте семейство API.");
            Set<String> writable = new HashSet<>();
            for (JsonNode f : result.path("functions")) if (bool(f.path("type").asText())) writable.add(f.path("code").asText());
            for (JsonNode p : result.path("status")) out.add(new Definition(p.path("code").asText(), p.path("type").asText(),
                parse(p.path("values")), writable.contains(p.path("code").asText())));
        }
        return out;
    }
    public JsonNode statuses(JsonNode status) {
        JsonNode r = status.path("result");
        JsonNode list = r.isArray() ? r : r.path("properties");
        if (!list.isArray()) throw new DemoException("SCHEMA", "Ответ не содержит показателей устройства. /state сообщает только блокировку, а не реле. Проверьте семейство API.");
        return list;
    }
    public Reading normalize(List<Definition> defs, JsonNode statuses, Map<String, String> overrides) {
        List<String> warnings = new ArrayList<>();
        Map<String, JsonNode> states = new HashMap<>();
        for (JsonNode s : statuses) {
            String code = s.path("code").asText();
            if (states.putIfAbsent(code, s) != null) {
                states.put(code, json.createObjectNode());
                warnings.add("Повторяющийся код состояния: " + code + ". Значение не используется.");
            }
        }
        String requested = overrides.getOrDefault("RELAY", "");
        List<Definition> relays = defs.stream().filter(d -> bool(d.type()) &&
            (requested.isBlank() ? d.code().matches("switch(?:_[0-9]+)?") : d.code().equals(requested))).toList();
        Definition relay = relays.size() == 1 ? relays.get(0) : null;
        if (relay == null) warnings.add("Реле не определено однозначно. Сверьте спецификацию и задайте TUYA_RELAY_CODE (Boolean/bool).");
        JsonNode rv = relay == null ? json.nullNode() : states.getOrDefault(relay.code(), json.createObjectNode()).path("value");
        Boolean on = rv.isBoolean() ? rv.asBoolean() : null;
        if (relay != null && on == null) warnings.add("Нет булевого показания выбранного реле.");
        Map<String, Metric> metrics = new LinkedHashMap<>();
        metrics.put("power", metric("POWER", "cur_power", "W", defs, states, overrides, warnings));
        metrics.put("voltage", metric("VOLTAGE", "cur_voltage", "V", defs, states, overrides, warnings));
        metrics.put("current", metric("CURRENT", "cur_current", "A", defs, states, overrides, warnings));
        metrics.put("energy", metric("ENERGY", "add_ele", "kWh", defs, states, overrides, warnings));
        if (metrics.get("energy").value() != null) warnings.add("Энергия — значение показателя устройства; накопительный характер и период из этого API не следуют.");
        return new Reading(relay == null ? null : relay.code(), on,
            relay == null ? null : reportTime(states.get(relay.code())), relay != null && relay.writable(), metrics, warnings);
    }
    private Metric metric(String key, String defaultCode, String targetUnit, List<Definition> defs,
                          Map<String, JsonNode> states, Map<String, String> overrides, List<String> warnings) {
        String code = overrides.getOrDefault(key, "");
        if (code.isBlank()) code = defaultCode;
        final String selected = code;
        List<Definition> candidates = defs.stream().filter(d -> d.code().equals(selected)).toList();
        if (candidates.size() != 1) return empty(selected, targetUnit, "Нет однозначной спецификации; при другом коде задайте TUYA_" + key + "_CODE.");
        Definition d = candidates.get(0);
        JsonNode values = d.values(), raw = states.getOrDefault(selected, json.createObjectNode()).path("value");
        String unit = values.path("unit").asText("");
        JsonNode scaleNode = values.path("scale");
        if (!Set.of("integer", "value", "float", "double").contains(d.type().toLowerCase(Locale.ROOT)) || !raw.isNumber()
            || !scaleNode.isIntegralNumber() || scaleNode.asInt() < 0 || scaleNode.asInt() > 12) {
            warnings.add(selected + ": отсутствует числовое значение, тип или явный корректный scale; масштаб не угадан.");
            return empty(selected, targetUnit, "Не удалось подтвердить тип и масштаб.");
        }
        BigDecimal multiplier = conversion(unit, targetUnit);
        if (multiplier == null) {
            warnings.add(selected + ": неизвестная или отсутствующая единица; значение не пересчитано.");
            return empty(selected, targetUnit, "Единица не подтверждена спецификацией.");
        }
        // Exact decimal arithmetic; numeric output is a string to avoid JS rounding.
        BigDecimal normalized = new BigDecimal(raw.asText()).movePointLeft(scaleNode.asInt()).multiply(multiplier);
        return new Metric(selected, normalized.stripTrailingZeros().toPlainString(), targetUnit, scaleNode.asInt(),
            reportTime(states.get(selected)), "Масштаб и единица взяты из спецификации: " + unit + ", scale=" + scaleNode.asInt());
    }
    private BigDecimal conversion(String source, String target) {
        if (source.equalsIgnoreCase(target)) return BigDecimal.ONE;
        return switch (source + ":" + target) {
            case "kW:W", "kw:W" -> new BigDecimal("1000");
            case "mW:W", "mV:V", "mA:A", "Wh:kWh", "wh:kWh" -> new BigDecimal("0.001");
            default -> null;
        };
    }
    private Metric empty(String code, String unit, String note) { return new Metric(code, null, unit, null, null, note); }
    private boolean bool(String type) { return type.equalsIgnoreCase("Boolean") || type.equalsIgnoreCase("bool"); }
    private JsonNode parse(JsonNode value) {
        try { return value.isString() ? json.readTree(value.asText()) : value; }
        catch (RuntimeException e) { throw new DemoException("SCHEMA", "Tuya вернула некорректные метаданные JSON."); }
    }
    public static String reportTime(JsonNode state) {
        if (state == null || !state.path("time").isIntegralNumber()) return null;
        long ms = state.path("time").asLong();
        if (ms < 946684800000L || ms > 4102444800000L) return null;
        return Instant.ofEpochMilli(ms).toString();
    }
}
