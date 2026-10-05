package foodAPIs.driverModule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import utils.ConfigReader;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Driver-side chat message to the customer - the driver app has no web UI, so this
 * does what the app does: publishes over MQTT.
 *
 * CONFIRMED by capturing what the customer web app publishes when it sends a chat
 * message (frame sent on wss://dev-mqtt.hoponbd.com/mqtt, QoS 1):
 * <pre>
 *   topic   ride/message
 *   payload {"type":"IncomingRideMessage","referenceId":"&lt;order uuid&gt;","message":"...",
 *            "senderId":"&lt;sender user id&gt;","senderSource":"RIDER",
 *            "receiverId":"&lt;driver user id&gt;","receiverSource":"DRIVER","service":"Food"}
 * </pre>
 * A driver reply is the mirror image (senderSource DRIVER, receiverSource RIDER). Login to
 * the broker mirrors the customer web app: username = user id (the JWT "sub"), password =
 * the access token (here the driver bearer token from TestData.properties).
 */
public class DriverChat implements AutoCloseable {

    private static final String BROKER_DEFAULT = "wss://dev-mqtt.hoponbd.com/mqtt";
    private static final String TOPIC = "ride/message";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String driverId;
    private final Mqtt5BlockingClient client;

    public DriverChat(String driverBearerToken) throws Exception {
        this.driverId = userIdFromJwt(driverBearerToken);

        String brokerUrl = ConfigReader.mqttBrokerUrl();
        URI uri = URI.create(brokerUrl.isEmpty() ? BROKER_DEFAULT : brokerUrl);
        boolean tls = "wss".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() != -1 ? uri.getPort() : (tls ? 443 : 80);
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/mqtt" : uri.getRawPath();

        client = Mqtt5Client.builder()
                .identifier("Driver_" + UUID.randomUUID().toString().replace("-", ""))
                .serverHost(uri.getHost())
                .serverPort(port)
                .webSocketConfig(com.hivemq.client.mqtt.MqttWebSocketConfig.builder().serverPath(path).build())
                .sslWithDefaultConfig()
                .simpleAuth()
                    .username(driverId)
                    .password(driverBearerToken.getBytes(StandardCharsets.UTF_8))
                    .applySimpleAuth()
                .buildBlocking();
        client.toAsync().connectWith().cleanStart(true).keepAlive(30).send().get(15, TimeUnit.SECONDS);
    }

    /** Sends one chat message from the driver to the customer of the given order. */
    public void send(String orderUuid, String customerId, String text) {
        ObjectNode body = mapper.createObjectNode()
                .put("type", "IncomingRideMessage")
                .put("referenceId", orderUuid)
                .put("message", text)
                .put("senderId", driverId)
                .put("senderSource", "DRIVER")
                .put("receiverId", customerId)
                .put("receiverSource", "RIDER")
                .put("service", "Food");
        try {
            client.publishWith()
                    .topic(TOPIC)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .payload(mapper.writeValueAsBytes(body))
                    .send();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public String driverId() {
        return driverId;
    }

    @Override
    public void close() {
        try {
            client.disconnect();
        } catch (RuntimeException ignored) {
            // already disconnected
        }
    }

    /** Reads the user id ("sub") out of a JWT without verifying it. */
    static String userIdFromJwt(String jwt) throws Exception {
        String[] parts = jwt.split("[.]");
        if (parts.length < 2) {
            throw new IllegalArgumentException("driver.auth.token is not a JWT");
        }
        JsonNode claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[1]));
        return claims.path("sub").asText();
    }
}
