package dev.localmesh.controller.health;

import dev.localmesh.controller.repository.InterceptRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * GET /health — liveness probe endpoint for Kubernetes.
 */
@RestController
public class HealthController {

    private final InterceptRepository interceptRepository;

    public HealthController(InterceptRepository interceptRepository) {
        this.interceptRepository = interceptRepository;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        int interceptCount = interceptRepository.countActiveIntercepts();
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "interceptCount", interceptCount,
                "timestamp", System.currentTimeMillis()
        ));
    }
}
