package net.myitems.tuyademo;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class QueueMonitor {
    public record Status(boolean enabled, String connection, String environment, long received, long applied,
        long rejected, String lastMessageAt, String lastDeviceEventAt, String message, List<String> lastCodes) {}
    private final TuyaConfig config;
    private String connection = "disabled", message = "Получение событий выключено в конфигурации.";
    private long received, applied, rejected;
    private String lastMessageAt, lastDeviceEventAt;
    private List<String> lastCodes = List.of();
    public QueueMonitor(TuyaConfig config) { this.config = config; }
    public boolean enabled() { return Boolean.parseBoolean(config.get("TUYA_MQ_ENABLED")); }
    public String environment() { return config.get("TUYA_MQ_ENV").isBlank() ? "PROD" : config.get("TUYA_MQ_ENV"); }
    public synchronized Status status() { return new Status(enabled(), connection, environment(), received, applied, rejected, lastMessageAt, lastDeviceEventAt, message, lastCodes); }
    public synchronized void connection(String value, String text) { connection = value; message = text; }
    public synchronized void received() { received++; lastMessageAt = Instant.now().toString(); }
    public synchronized void applied(List<String> codes) { applied++; lastDeviceEventAt = Instant.now().toString(); lastCodes = List.copyOf(codes); }
    public synchronized void rejected() { rejected++; message = "Сообщение не применено: не удалось подтвердить формат или AES-GCM. Исходные данные не публикуются."; }
}
