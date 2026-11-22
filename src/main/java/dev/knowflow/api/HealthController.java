package dev.knowflow.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

@RestController
public class HealthController {
    private final DataSource db;
    private final boolean modelConfigured;
    private final URI milvusHealth;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public HealthController(DataSource db, @Value("${knowflow.ai.api-key}") String key,
            @Value("${knowflow.milvus.health-url:http://127.0.0.1:19091/healthz}") String healthUrl) {
        this.db = db; this.modelConfigured = !key.isBlank(); this.milvusHealth = URI.create(healthUrl);
    }
    @GetMapping("/api/health")
    ResponseEntity<?> health(HttpServletRequest request) {
        boolean databaseUp = false, milvusUp = false;
        try (var connection = db.getConnection()) { databaseUp = connection.isValid(2); } catch (Exception ignored) { }
        try {
            milvusUp = http.send(HttpRequest.newBuilder(milvusHealth).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception ignored) { }
        boolean ready = databaseUp && milvusUp && modelConfigured;
        return ResponseEntity.status(ready ? 200 : 503).body(Map.of("status", ready ? "READY" : "DEGRADED", "database", databaseUp ? "UP" : "DOWN",
                "milvus", milvusUp ? "UP" : "DOWN", "model", modelConfigured ? "CONFIGURED_NOT_PROBED" : "NOT_CONFIGURED", "requestId", RequestIds.get(request)));
    }
}
