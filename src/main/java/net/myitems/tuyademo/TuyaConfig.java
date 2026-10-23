package net.myitems.tuyademo;

import java.net.URI;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class TuyaConfig {
    private final Environment env;
    private static final Set<String> HOSTS = Set.of("openapi.tuyacn.com", "openapi.tuyaus.com",
        "openapi-ueaz.tuyaus.com", "openapi.tuyaeu.com", "openapi-weaz.tuyaeu.com",
        "openapi.tuyain.com", "openapi-sg.iotbing.com");
    public TuyaConfig(Environment env) { this.env = env; }
    public String get(String key) { return env.getProperty(key, "").trim(); }
    public List<String> missing() {
        return List.of("TUYA_CLIENT_ID", "TUYA_CLIENT_SECRET", "TUYA_ENDPOINT", "TUYA_DEVICE_ID")
            .stream().filter(k -> get(k).isBlank() || get(k).equals("REPLACE_ME")).toList();
    }
    public void validate() {
        if (!missing().isEmpty()) throw new DemoException("CONFIG", "Заполните tuya.local.properties: " + String.join(", ", missing()) + ". Затем перезапустите приложение.");
        try {
            URI u = URI.create(get("TUYA_ENDPOINT"));
            boolean allowed = HOSTS.contains(u.getHost());
            if (!"https".equals(u.getScheme()) || !allowed || u.getPort() != -1 || u.getUserInfo() != null
                || u.getQuery() != null || u.getFragment() != null || !(u.getPath().isEmpty() || u.getPath().equals("/"))) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new DemoException("CONFIG", "TUYA_ENDPOINT должен быть региональным HTTPS endpoint Tuya без пути и параметров."); }
        if (!get("TUYA_DEVICE_ID").matches("[A-Za-z0-9_-]{1,128}")) throw new DemoException("CONFIG", "Проверьте TUYA_DEVICE_ID.");
        if (!Set.of("DEVICES_V1", "IOT03", "CLOUD_V2").contains(family())) throw new DemoException("CONFIG", "TUYA_API_FAMILY: допустимы CLOUD_V2, DEVICES_V1 и IOT03.");
    }
    public String family() { return get("TUYA_API_FAMILY").isBlank() ? "CLOUD_V2" : get("TUYA_API_FAMILY"); }
    public String base() { return get("TUYA_ENDPOINT").replaceAll("/$", ""); }
    public String devicePath(String part) {
        if (family().equals("CLOUD_V2")) return "/v2.0/cloud/thing/" + get("TUYA_DEVICE_ID") + switch (part) {
            case "info" -> "";
            case "spec" -> "/model";
            case "status" -> "/shadow/properties";
            case "commands" -> "/shadow/properties/issue";
            default -> throw new IllegalArgumentException("Unsupported operation");
        };
        String root = family().equals("IOT03") ? "/v1.0/iot-03/devices/" : "/v1.0/devices/";
        return root + get("TUYA_DEVICE_ID") + switch (part) {
            case "spec" -> family().equals("IOT03") ? "/specification" : "/specifications";
            case "info" -> "";
            default -> "/" + part;
        };
    }
    public String infoPath() { return family().equals("IOT03") ? "/v1.1/iot-03/devices/" + get("TUYA_DEVICE_ID") : devicePath("info"); }
}
