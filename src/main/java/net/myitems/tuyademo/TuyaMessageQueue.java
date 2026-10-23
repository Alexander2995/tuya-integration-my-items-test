package net.myitems.tuyademo;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.apache.pulsar.client.api.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

@Component
public class TuyaMessageQueue {
    private final TuyaConfig config;
    private final QueueMonitor monitor;
    private final DeviceService devices;
    private final ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "tuya-mq"); t.setDaemon(true); return t; });
    private volatile boolean stopping;
    private volatile PulsarClient client;
    private volatile Consumer<byte[]> consumer;
    public TuyaMessageQueue(TuyaConfig config, QueueMonitor monitor, DeviceService devices) {
        this.config = config; this.monitor = monitor; this.devices = devices;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void start() { if (monitor.enabled()) worker.submit(this::run); }
    private String serviceUrl() {
        return switch (URI.create(config.base()).getHost()) {
            case "openapi.tuyaeu.com" -> "pulsar+ssl://mqe.tuyaeu.com:7285/";
            case "openapi.tuyaus.com" -> "pulsar+ssl://mqe.tuyaus.com:7285/";
            case "openapi.tuyacn.com" -> "pulsar+ssl://mqe.tuyacn.com:7285/";
            case "openapi.tuyain.com" -> "pulsar+ssl://mqe.tuyain.com:7285/";
            case "openapi-sg.iotbing.com" -> "pulsar+ssl://mqe-sg.iotbing.com:7285/";
            default -> throw new DemoException("MQ_REGION", "Для выбранного региона требуется уточнить официальный endpoint очереди.");
        };
    }
    private void run() {
        String url;
        try {
            config.validate(); url = serviceUrl();
            if (!config.family().equals("CLOUD_V2") || !Set.of("PROD", "TEST").contains(monitor.environment()) || config.get("TUYA_CLIENT_SECRET").length() < 24)
                throw new DemoException("MQ_CONFIG", "Проверьте CLOUD_V2, ключ проекта и TUYA_MQ_ENV=PROD или TEST.");
        } catch (DemoException | IllegalArgumentException e) { monitor.connection("error", "Не удалось настроить очередь. Проверьте регион, ключи, семейство CLOUD_V2 и среду PROD/TEST."); return; }
        int retrySeconds = 2;
        while (!stopping) {
            try {
                monitor.connection("connecting", "Подключение к Message Queue Tuya…");
                client = PulsarClient.builder().serviceUrl(url).connectionTimeout(7, TimeUnit.SECONDS)
                    .operationTimeout(12, TimeUnit.SECONDS).ioThreads(1).listenerThreads(1)
                    .allowTlsInsecureConnection(false).enableTlsHostnameVerification(true)
                    .authentication(new TuyaQueueAuthentication(config.get("TUYA_CLIENT_ID"), config.get("TUYA_CLIENT_SECRET"))).build();
                String topic = "persistent://" + config.get("TUYA_CLIENT_ID") + "/out/" + (monitor.environment().equals("TEST") ? "event-test" : "event");
                consumer = client.newConsumer(Schema.BYTES).topic(topic).subscriptionName(config.get("TUYA_CLIENT_ID") + "-sub")
                    .subscriptionType(SubscriptionType.Failover).autoUpdatePartitions(false).receiverQueueSize(50)
                    .subscriptionInitialPosition(SubscriptionInitialPosition.Latest).subscribe();
                monitor.connection("connected", "Подписка установлена. Ожидание событий розетки; при отсутствии сообщений проверьте правила и среду в Tuya.");
                devices.reconcile();
                retrySeconds = 2;
                boolean connected = true;
                while (!stopping) {
                    boolean now = consumer.isConnected();
                    if (now != connected) {
                        monitor.connection(now ? "connected" : "reconnecting", now ? "Подписка восстановлена. Ожидание событий." : "Соединение очереди прервано. Выполняется переподключение.");
                        if (now) devices.reconcile();
                        connected = now;
                    }
                    Message<byte[]> message = consumer.receive(1, TimeUnit.SECONDS);
                    if (message == null) continue;
                    monitor.received();
                    try {
                        if (message.getData().length > 1_500_000) throw new IllegalArgumentException();
                        JsonNode envelope = json.readTree(new String(message.getData(), StandardCharsets.UTF_8));
                        String plain = TuyaQueueCrypto.decrypt(envelope.path("data").asText(), message.getProperty("em"), config.get("TUYA_CLIENT_SECRET"));
                        List<String> changed = devices.applyEvent(json.readTree(plain), envelope.path("protocol").asInt());
                        if (!changed.isEmpty()) monitor.applied(changed);
                    } catch (Exception e) {
                        // Acknowledge poison/unsupported messages without logging private payloads.
                        monitor.rejected();
                    }
                    consumer.acknowledge(message);
                }
            } catch (Exception e) {
                if (!stopping) monitor.connection("error", "Очередь недоступна. Проверьте Message Service, регион, PROD/TEST и исходящий порт 7285. Повторное подключение выполняется автоматически.");
            } finally { closeConnections(); }
            if (!stopping) try { Thread.sleep(retrySeconds * 1000L); retrySeconds = Math.min(60, retrySeconds * 2); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }
    private void closeConnections() {
        Consumer<byte[]> c = consumer; consumer = null;
        if (c != null) try { c.close(); } catch (Exception ignored) {}
        PulsarClient p = client; client = null;
        if (p != null) try { p.close(); } catch (Exception ignored) {}
    }
    @PreDestroy public void stop() { stopping = true; worker.shutdownNow(); closeConnections(); }
}
