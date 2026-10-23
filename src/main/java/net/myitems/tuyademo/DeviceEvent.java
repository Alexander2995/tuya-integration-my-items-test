package net.myitems.tuyademo;

import java.math.BigDecimal;
import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ArrayNode;

/** Supported Tuya protocols: legacy 4/20 and IoT Core 1000/1001. */
public record DeviceEvent(String deviceId, String dataId, JsonNode properties, Boolean online, long onlineTime) {
    private static final ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    public static DeviceEvent parse(JsonNode payload, int protocol, List<DeviceNormalizer.Definition> definitions) {
        JsonNode business = payload.path("bizData").isObject() ? payload.path("bizData") : payload;
        String device = business.path("devId").asText(payload.path("devId").asText(""));
        String dataId = business.path("dataId").asText(payload.path("dataId").asText(""));
        String kind = payload.path("bizCode").asText("");
        Boolean online = switch (kind) {
            case "online", "deviceOnline" -> true;
            case "offline", "deviceOffline" -> false;
            default -> null;
        };
        if (online != null && protocol != 20 && protocol != 1001) online = null;
        long onlineTime = business.path("time").asLong(0);
        ArrayNode rows = JSON.createArrayNode();
        JsonNode source = protocol == 4 ? payload.path("status")
            : protocol == 1000 && Set.of("devicePropertyMessage", "deviceQueryPropertyMessage").contains(kind)
                ? business.path("properties") : JSON.createArrayNode();
        Map<String, DeviceNormalizer.Definition> byCode = new HashMap<>();
        for (var d : definitions) byCode.put(d.code(), d);
        if (source.isArray()) for (JsonNode row : source) {
            String code = row.path("code").asText("");
            var definition = byCode.get(code);
            if (definition == null) continue;
            JsonNode value = row.path("value");
            String type = definition.type().toLowerCase(Locale.ROOT);
            if (value.isString()) {
                String text = value.asText();
                if (Set.of("bool", "boolean").contains(type) && Set.of("true", "false").contains(text)) value = JSON.valueToTree(Boolean.parseBoolean(text));
                else if (Set.of("value", "integer", "float", "double").contains(type) && text.length() < 100 && text.matches("-?[0-9]+(?:\\.[0-9]+)?")) value = JSON.valueToTree(new BigDecimal(text));
                else continue;
            }
            if ((Set.of("bool", "boolean").contains(type) && !value.isBoolean()) ||
                (Set.of("value", "integer", "float", "double").contains(type) && !value.isNumber())) continue;
            if (!value.isBoolean() && !value.isNumber()) continue;
            JsonNode time = row.path(protocol == 4 ? "t" : "time");
            var normalized = JSON.createObjectNode().put("code", code).set("value", value);
            normalized.set("time", time);
            // Do not substitute the message-envelope timestamp for property report time.
            if (DeviceNormalizer.reportTime(normalized) != null) rows.add(normalized);
        }
        return new DeviceEvent(device, dataId, rows, online, onlineTime);
    }
}
