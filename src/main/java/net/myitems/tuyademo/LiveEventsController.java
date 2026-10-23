package net.myitems.tuyademo;

import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api")
public class LiveEventsController {
    public record Update(DeviceService.State state, QueueMonitor.Status queue) {}
    private final DeviceService devices;
    private final QueueMonitor queue;
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentHashMap<SseEmitter, Boolean> clients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "local-sse"); t.setDaemon(true); return t; });
    private String previous = "";
    private long ticks;
    public LiveEventsController(DeviceService devices, QueueMonitor queue) {
        this.devices = devices; this.queue = queue;
        scheduler.scheduleWithFixedDelay(this::broadcast, 1, 1, TimeUnit.SECONDS);
    }
    @GetMapping("/queue") public QueueMonitor.Status queue() { return queue.status(); }
    @GetMapping(value="/events", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        if (clients.size() >= 10) throw new DemoException("BUSY", "Открыто слишком много вкладок приложения.");
        SseEmitter emitter = new SseEmitter(0L);
        clients.put(emitter, true);
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> clients.remove(emitter));
        emitter.onError(e -> clients.remove(emitter));
        try { emitter.send(SseEmitter.event().name("snapshot").data(new Update(devices.state(), queue.status()))); }
        catch (Exception e) { clients.remove(emitter); emitter.complete(); }
        return emitter;
    }
    private void broadcast() {
        try {
            String current = json.writeValueAsString(new Update(devices.state(), queue.status()));
            boolean changed = !current.equals(previous); previous = current;
            boolean heartbeat = ++ticks % 15 == 0;
            if (!changed && !heartbeat) return;
            for (SseEmitter emitter : clients.keySet()) try {
                emitter.send(changed ? SseEmitter.event().name("snapshot").data(current, MediaType.APPLICATION_JSON)
                    : SseEmitter.event().comment("keepalive"));
            } catch (Exception e) { clients.remove(emitter); emitter.complete(); }
        } catch (RuntimeException ignored) { /* Never print DTO or exception contents. */ }
    }
    @PreDestroy public void stop() { scheduler.shutdownNow(); clients.keySet().forEach(SseEmitter::complete); clients.clear(); }
}
