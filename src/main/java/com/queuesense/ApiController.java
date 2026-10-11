package com.queuesense;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
public class ApiController {
    public record CheckInRequest(Long venue_id, String device_hash, String name, Double hours) {}
    public record CheckOutRequest(Long venue_id, String device_hash) {}

    private final QueueService service;
    private final String adminKey;

    public ApiController(QueueService service, @Value("${queuesense.admin-key}") String adminKey) {
        this.service = service;
        this.adminKey = adminKey;
    }

    @GetMapping(value = "/", produces = {org.springframework.http.MediaType.TEXT_HTML_VALUE, org.springframework.http.MediaType.APPLICATION_JSON_VALUE})
    public Object root() throws java.io.IOException {
        var res = getClass().getResource("/static/index.html");
        if (res != null) {
            try (var in = res.openStream()) {
                return org.springframework.http.ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.TEXT_HTML)
                        .body(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return Map.of("service", "QueueSense API", "status", "ok");
    }

    @GetMapping({"/health", "/api/health"})
    Map<String, String> health() { return Map.of("service", "QueueSense API", "status", "ok"); }

    @PostMapping("/checkin")
    Map<String, Object> checkin(@RequestBody CheckInRequest r) {
        return service.checkIn(r.venue_id(), r.device_hash(), r.name(), r.hours());
    }

    @PostMapping("/checkout")
    Map<String, Object> checkout(@RequestBody CheckOutRequest r) {
        return service.checkOut(r.venue_id(), r.device_hash());
    }

    @GetMapping("/venues/live")
    List<Map<String, Object>> live(@RequestParam(name = "device_hash", required = false) String deviceHash) {
        return service.live(deviceHash);
    }

    @GetMapping("/venues/compare")
    List<Map<String, Object>> compare() { return service.compare(); }

    @GetMapping("/venues/{id}/today")
    List<Map<String, Object>> today(@PathVariable long id) { return service.today(id); }

    @GetMapping("/venues/{id}/heatmap")
    Map<String, Object> heatmap(@PathVariable long id) { return service.heatmap(id); }

    @GetMapping("/admin/stats")
    List<Map<String, Object>> admin(@RequestHeader(name = "x-admin-key", required = false) String key) {
        if (!adminKey.equals(key)) throw new ApiException(401, "Invalid admin key");
        return service.compare();
    }
}
