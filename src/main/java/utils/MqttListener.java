package utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * Real-time order-status listener for web (customer + restaurant-admin).
 *
 * CONFIRMED, END-TO-END, LIVE (2026-09-30): {@link #connectFromWebSession} successfully
 * logged a real customer in, connected to the real broker over WSS, and subscribed to
 * that session's real topic — not just "compiles", an actual broker round-trip. Two real
 * bugs were caught and fixed getting here (see git history around this date): a
 * `.transportConfig(...)` call was silently discarding the `.serverHost()`/`.serverPort()`
 * set earlier in the same builder chain (client was connecting to localhost:1883 instead
 * of the real broker), and `hivemq-mqtt-client`'s WebSocket support needs
 * `io.netty:netty-codec-http` added explicitly (it's an optional transitive dependency
 * the library doesn't pull in on its own — omitting it throws NoClassDefFoundError on
 * WebSocketVersion at connect time, not at compile time).
 *
 * Connection details, decompiled straight from
 * {@code src/app/features/order-management/services/mqtt-message.service.ts} on
 * dev-food.hoponbd.com:
 *   - broker: {@code wss://dev-mqtt.hoponbd.com/mqtt} (environment.mqttUrl) — MQTT
 *     v5 over WebSocket (the real client uses mqtt.js with protocolVersion: 5), which
 *     is why this uses the HiveMQ MQTT client rather than Paho's v3-only module.
 *   - auth: username = the logged-in user's id, password = the same JWT access token
 *     used for REST API calls. Both, plus the subscribe topic and a stable clientId,
 *     are session cookies/localStorage set right after login — NOT static config:
 *     <pre>
 *       _ssl_mt   (cookie)       subscribe topic, e.g. "rider/{userId}" for a
 *                                 customer session (confirmed live) — restaurant-admin's
 *                                 topic prefix is NOT yet confirmed, capture it the same
 *                                 way (DevTools > Application > Cookies) before relying on it
 *       _ssl_mu   (cookie)       username = the user id
 *       _ssl_taf  (cookie)       JWT access token, used as the MQTT password
 *       _ssl_mcid (localStorage) stable per-device clientId
 *     </pre>
 *     {@link #connectFromWebSession(Page)} reads these directly off the Playwright
 *     session after UI login, exactly like the real app does — no guessed values.
 *   - subscribe QoS 2, clean session, keepalive 30s (matches the real client).
 *   - message body: flat JSON matching the order-status shape already confirmed via
 *     DriverAPI (orderNo, status, statusProgresses, driverInfo, ...) at the top level.
 *     The real client also unwraps a nested {@code {msg:"..."}} or
 *     {@code {data:{content:{...}}}} envelope, but every message captured live so far
 *     was already flat, so {@link #waitForOrderStatus} reads {@code orderNo} directly.
 */
public class MqttListener implements AutoCloseable {

    private static final String DEFAULT_BROKER_URL = "wss://dev-mqtt.hoponbd.com/mqtt";

    private final ObjectMapper mapper = new ObjectMapper();
    private Mqtt5BlockingClient client;
    private Mqtt5BlockingClient.Mqtt5Publishes publishes;

    /**
     * Reads the MQTT session cookies/localStorage off an already-logged-in Playwright
     * page, connects, and subscribes — the whole thing, in one call. Throws if the
     * page isn't logged in yet (cookies not present).
     */
    public static MqttListener connectFromWebSession(Page page) throws Exception {
        String topic = null;
        String username = null;
        String accessToken = null;
        for (Cookie cookie : page.context().cookies()) {
            switch (cookie.name) {
                case "_ssl_mt" -> topic = URLDecoder.decode(cookie.value, StandardCharsets.UTF_8);
                case "_ssl_mu" -> username = cookie.value;
                case "_ssl_taf" -> accessToken = cookie.value;
                default -> { }
            }
        }
        if (topic == null || username == null || accessToken == null) {
            throw new IllegalStateException(
                    "Missing MQTT session cookies (_ssl_mt/_ssl_mu/_ssl_taf) - the page must be " +
                    "logged in before calling connectFromWebSession(). See MqttListener's class Javadoc.");
        }

        Object rawClientId = page.evaluate("() => localStorage.getItem('_ssl_mcid')");
        String clientId = rawClientId == null ? "" : String.valueOf(rawClientId);
        if (clientId.isBlank()) {
            clientId = UUID.randomUUID().toString();
        }

        MqttListener listener = new MqttListener();
        listener.connect(clientId, username, accessToken);
        listener.subscribe(topic);
        return listener;
    }

    /** Lower-level entry point when you already have the credentials some other way. */
    public MqttListener connect(String clientId, String username, String accessToken) throws Exception {
        String brokerUrl = ConfigReader.mqttBrokerUrl();
        if (brokerUrl.isEmpty()) {
            brokerUrl = DEFAULT_BROKER_URL;
        }
        URI uri = URI.create(brokerUrl);
        boolean useTls = "wss".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() != -1 ? uri.getPort() : (useTls ? 443 : 80);
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/mqtt" : uri.getRawPath();

        Mqtt5BlockingClient rawClient = Mqtt5Client.builder()
                .identifier(clientId)
                .serverHost(uri.getHost())
                .serverPort(port)
                .webSocketConfig(com.hivemq.client.mqtt.MqttWebSocketConfig.builder().serverPath(path).build())
                .sslWithDefaultConfig()
                .simpleAuth()
                    .username(username)
                    .password(accessToken.getBytes(StandardCharsets.UTF_8))
                    .applySimpleAuth()
                .buildBlocking();

        client = rawClient;
        publishes = client.publishes(com.hivemq.client.mqtt.MqttGlobalPublishFilter.ALL);
        client.toAsync().connectWith()
                .cleanStart(true)
                .keepAlive(30)
                .send()
                .get(15, java.util.concurrent.TimeUnit.SECONDS);
        System.out.println(timestamp() + " [MQTT] Connected: " + clientId);
        return this;
    }

    public MqttListener subscribe(String topic) {
        client.subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.EXACTLY_ONCE)
                .send();
        System.out.println(timestamp() + " [MQTT] Subscribed to: " + topic);
        return this;
    }

    /**
     * Blocks (up to timeoutSeconds) until a message whose top-level "orderNo" field
     * matches arrives, and returns the parsed payload — or null on timeout. Messages
     * for other orders are drained and discarded while waiting.
     */
    public JsonNode waitForOrderStatus(String orderNo, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            long remainingMs = deadline - System.currentTimeMillis();
            Optional<Mqtt5Publish> received = publishes.receive(Math.max(remainingMs, 0), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (received.isEmpty()) {
                return null;
            }
            String raw = new String(received.get().getPayloadAsBytes(), StandardCharsets.UTF_8);
            System.out.println(timestamp() + " [MQTT] Message: " + raw);
            JsonNode root = mapper.readTree(raw);
            if (orderNo.equals(root.path("orderNo").asText())) {
                return root;
            }
        }
        return null;
    }

    public void disconnect() {
        if (client != null) {
            try {
                client.disconnect();
            } catch (Exception e) {
                System.out.println(timestamp() + " [MQTT] Error during disconnect: " + e.getMessage());
            }
            System.out.println(timestamp() + " [MQTT] Disconnected");
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    private static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
