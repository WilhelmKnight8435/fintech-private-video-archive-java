package example.fintech;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@SpringBootApplication
@RestController
public class SessionArchive {
    private final InfraiCalls api;
    private final String bucket;

    public SessionArchive(@Value("${archive.base-url}") String baseUrl,
                          @Value("${archive.bucket}") String bucket, ObjectMapper mapper) {
        this.api = new InfraiCalls(baseUrl, System.getenv("INFRAI_API_KEY"), mapper);
        this.bucket = bucket;
    }

    public static void main(String[] args) { SpringApplication.run(SessionArchive.class, args); }

    public record PaymentSession(String eventId, String customerId, String riskLevel) {}
    public record SessionHandoff(String decision, String room, String roomToken, String uploadUrl, String objectKey) {}

    @PostMapping("/payment-sessions")
    public SessionHandoff open(@RequestBody PaymentSession payment) throws Exception {
        if (payment.eventId() == null || !payment.eventId().matches("[A-Za-z0-9_-]{1,64}")
                || payment.customerId() == null || !payment.customerId().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("eventId and customerId must be short URL-safe identifiers");
        }
        String decision = RiskDecision.forLevel(payment.riskLevel());
        String room = "payment-" + payment.eventId();
        String channel = "audit-" + payment.eventId();
        String objectKey = "payments/" + payment.eventId() + "/session.webm";

        // One server key and one base URL authorize the room and its private archive.
        api.call("POST", "/v1/storage/bucket/create", Map.of("name", bucket));
        api.call("POST", "/v1/realtime/channel/create", Map.of("channel", channel));
        api.call("POST", "/v1/realtime/publish", Map.of("channel", channel,
                "event", "payment.session.decision", "data", Map.of("eventId", payment.eventId(), "decision", decision),
                "account_id", payment.customerId()));
        if (!decision.equals("APPROVED")) return new SessionHandoff(decision, null, null, null, null);

        api.call("POST", "/v1/rtc/room/create", Map.of("name", room));
        JsonNode token = api.call("POST", "/v1/rtc/token/issue", Map.of("room", room,
                "identity", payment.customerId(), "can_publish", true, "can_subscribe", true));
        JsonNode upload = api.call("POST", "/v1/storage/object/presign/" + segment(bucket) + "/" + path(objectKey),
                Map.of("op", "put", "expires_seconds", 900, "content_type", "video/webm",
                        "idempotency_key", payment.eventId() + "-recording"));
        return new SessionHandoff(decision, room, token.path("token").asText(), upload.path("url").asText(), objectKey);
    }

    static String segment(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    static String path(String value) { return String.join("/", java.util.Arrays.stream(value.split("/", -1)).map(SessionArchive::segment).toList()); }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InfraiCalls.ApiError.class)
    public ResponseEntity<Map<String, String>> apiError(InfraiCalls.ApiError e) {
        int status = e.status >= 400 && e.status < 500 ? e.status : 502;
        return ResponseEntity.status(HttpStatus.valueOf(status)).body(Map.of("error", e.code));
    }
}

final class RiskDecision {
    static String forLevel(String level) {
        if (level == null) throw new IllegalArgumentException("riskLevel is required");
        return switch (level.toUpperCase(java.util.Locale.ROOT)) {
            case "LOW", "MEDIUM" -> "APPROVED";
            case "HIGH" -> "REVIEW";
            default -> throw new IllegalArgumentException("riskLevel must be LOW, MEDIUM or HIGH");
        };
    }
}

final class InfraiCalls {
    private final String baseUrl;
    private final String key;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    InfraiCalls(String baseUrl, String key, ObjectMapper mapper) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("INFRAI_API_KEY is required");
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.key = key;
        this.mapper = mapper;
    }

    JsonNode call(String method, String path, Map<String, ?> fields) throws Exception {
        byte[] payload = mapper.writeValueAsBytes(fields);
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode envelope = mapper.readTree(response.body());
            if (response.statusCode() == 429 && attempt < 3 && (method.equals("GET") || path.contains("/presign/"))) {
                long seconds = response.headers().firstValue("Retry-After").flatMap(v -> {
                    try { return java.util.Optional.of(Long.parseLong(v)); }
                    catch (NumberFormatException e) { return java.util.Optional.empty(); }
                }).orElse(1L << attempt);
                Thread.sleep(Math.min(30, Math.max(1, seconds)) * 1000);
                continue;
            }
            if (!envelope.path("ok").asBoolean(false)) {
                throw new ApiError(envelope.path("error").path("code").asText("INFRAI_REQUEST_REJECTED"), response.statusCode());
            }
            if (response.statusCode() >= 500) throw new IllegalStateException("Upstream request failed");
            return envelope.path("data");
        }
        throw new IllegalStateException("Request attempts exhausted");
    }

    static final class ApiError extends RuntimeException {
        final String code;
        final int status;
        ApiError(String code, int status) { super(code); this.code = code; this.status = status; }
    }
}
