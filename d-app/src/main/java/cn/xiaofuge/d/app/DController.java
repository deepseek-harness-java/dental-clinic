package cn.xiaofuge.d.app;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 牙科诊所 REST API */
@org.springframework.stereotype.Component
@org.springframework.context.annotation.Configuration
@RestController
public class DController {

    private final DStore store;

    public DController(DStore store) { this.store = store; }

    @GetMapping("/api/doctors")
    public Map<String, Object> doctors() {
        return Map.of("code", 0, "data", store.doctorList());
    }

    @PostMapping("/api/book")
    public Map<String, Object> book(@RequestBody Map<String, Object> body) {
        return Map.of("code", 0, "data", store.book(
                String.valueOf(body.getOrDefault("patient", "")),
                String.valueOf(body.getOrDefault("doctorId", "")),
                String.valueOf(body.getOrDefault("item", ""))));
    }

    @GetMapping("/api/records")
    public Map<String, Object> records(@RequestParam String patient) {
        return Map.of("code", 0, "data", store.records(patient));
    }

    @PostMapping("/api/estimate")
    public Map<String, Object> estimate(@RequestBody Map<String, Object> body) {
        return Map.of("code", 0, "data", store.estimate(
                String.valueOf(body.getOrDefault("patient", "")),
                String.valueOf(body.getOrDefault("item", ""))));
    }

    @PostMapping("/api/cancel")
    public Map<String, Object> cancel(@RequestBody Map<String, Object> body) {
        return Map.of("code", 0, "data", store.cancel(String.valueOf(body.getOrDefault("apptId", ""))));
    }

    @GetMapping("/api/appts")
    public Map<String, Object> appts() {
        return Map.of("code", 0, "data", store.appts);
    }

    @GetMapping("/api/stats")
    public Map<String, Object> stats() {
        return Map.of("code", 0, "data", store.stats());
    }
}
