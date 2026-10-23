package net.myitems.tuyademo;

import java.util.*;
import tools.jackson.databind.JsonNode;

/** Allowlist only: never forward device info, tokens, identifiers or free text values. */
public final class Diagnostics {
    private Diagnostics() {}
    public static List<Map<String, Object>> status(JsonNode list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode s : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", safeCode(s.path("code").asText()));
            JsonNode v = s.path("value");
            row.put("value", v.isBoolean() ? v.asBoolean() : v.isNumber() ? v.asText() : "[скрыто: нечисловое значение]");
            String report = DeviceNormalizer.reportTime(s);
            if (report != null) row.put("reportedAt", report);
            out.add(row);
        }
        return out;
    }
    public static List<Map<String, Object>> specification(List<DeviceNormalizer.Definition> defs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var d : defs) {
            Map<String, Object> row = new LinkedHashMap<>(), metadata = new LinkedHashMap<>();
            row.put("code", safeCode(d.code())); row.put("type", safeCode(d.type())); row.put("writable", d.writable());
            for (String key : List.of("scale", "min", "max", "step")) if (d.values().path(key).isNumber()) metadata.put(key, d.values().path(key).asText());
            String unit = d.values().path("unit").asText("");
            metadata.put("unit", unit.matches("[a-zA-ZА-Яа-я0-9%°/²³ _.-]{0,20}") ? unit : "[скрыто]");
            row.put("values", metadata); out.add(row);
        }
        return out;
    }
    private static String safeCode(String s) { return s.matches("[a-zA-Z0-9_]{1,100}") ? s : "[скрыто]"; }
}
