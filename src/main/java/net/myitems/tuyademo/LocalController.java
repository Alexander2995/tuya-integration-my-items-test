package net.myitems.tuyademo;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class LocalController {
    private final DeviceService service;
    public LocalController(DeviceService service) { this.service = service; }
    @GetMapping("/snapshot") public DeviceService.State snapshot() { return service.state(); }
    @PostMapping("/refresh") public DeviceService.State refresh() { return service.refresh(); }
    public record Command(Boolean on) {}
    @PostMapping(value="/relay", consumes=MediaType.APPLICATION_JSON_VALUE)
    public DeviceService.CommandResult relay(@RequestBody Command command) {
        if (command.on() == null) throw new DemoException("INPUT", "Передайте on: true или false.");
        return service.command(command.on());
    }
    @ExceptionHandler(DemoException.class)
    public ResponseEntity<?> known(DemoException e) {
        return ResponseEntity.status(e.code.equals("BUSY") ? 409 : 400)
            .body(Map.of("error", new DeviceService.Problem(e.code, e.getMessage()), "state", service.state()));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception ignored) {
        return ResponseEntity.status(500).body(Map.of("error", new DeviceService.Problem("LOCAL", "Локальный запрос не выполнен. Проверьте формат запроса или перезапустите приложение.")));
    }
}
