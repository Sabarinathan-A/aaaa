package com.frauddetector.notify;

import com.frauddetector.domain.Notification;
import com.frauddetector.http.Json;
import com.frauddetector.service.NotificationService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Posts each notification as JSON to {@code NOTIFY_WEBHOOK_URL} (e.g. a Slack /
 * Teams / SMS-gateway relay). Sent asynchronously; failures are logged, never
 * propagated. Only http(s) URLs are accepted.
 */
public final class WebhookNotifier implements NotificationService.ExternalNotifier {

    private final URI url;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public WebhookNotifier(String url) {
        URI u = URI.create(url);
        if (!"https".equalsIgnoreCase(u.getScheme()) && !"http".equalsIgnoreCase(u.getScheme())) {
            throw new IllegalArgumentException("NOTIFY_WEBHOOK_URL must be http(s)");
        }
        this.url = u;
    }

    @Override
    public String name() {
        return "webhook(" + url.getHost() + ")";
    }

    @Override
    public void deliver(Notification n) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", n.getId());
        body.put("type", n.getType());
        body.put("severity", n.getSeverity());
        body.put("targetRole", n.getTargetRole().name());
        body.put("claimId", n.getClaimId());
        body.put("text", n.getMessage());
        body.put("createdAt", n.getCreatedAt() == null ? null : n.getCreatedAt().toString());
        HttpRequest req = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        client.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .whenComplete((res, err) -> {
                    if (err != null) {
                        System.err.println("WARN: webhook delivery of " + n.getId() + " failed: " + err.getMessage());
                    } else if (res.statusCode() >= 300) {
                        System.err.println("WARN: webhook returned HTTP " + res.statusCode() + " for " + n.getId());
                    }
                });
    }
}
