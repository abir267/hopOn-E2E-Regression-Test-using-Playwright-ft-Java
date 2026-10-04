package utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Prints the raw MQTT traffic a browser page sees, tagged by role, e.g.
 * {@code [MQTT] ← [CUSTOMER] 024-D74 · Accepted · "title"}.
 *
 * Hooks the page's MQTT WebSocket (wss://...mqtt) and decodes each PUBLISH packet
 * the broker pushes to that page, so what is printed is the real payload the app
 * received. Called from the page-object base
 * classes, so every customer/restaurant page is covered with no per-test code.
 */
public final class MqttConsoleCapture {

    private static final Map<Page, Boolean> ATTACHED = Collections.synchronizedMap(new WeakHashMap<>());

    private MqttConsoleCapture() {
    }

    public static void attach(Page page, String role) {
        if (ATTACHED.putIfAbsent(page, Boolean.TRUE) != null) {
            return;
        }
        page.onWebSocket(ws -> {
            if (!ws.url().toLowerCase().contains("mqtt")) {
                return;
            }
            ws.onFrameReceived(frame -> printPublishes(role, frame.binary()));
        });
    }

    /** One WebSocket frame can hold several MQTT packets; walk them and print each PUBLISH. */
    private static void printPublishes(String role, byte[] data) {
        try {
            int i = 0;
            while (data != null && i < data.length) {
                int first = data[i++] & 0xFF;
                int remaining = 0;
                int shift = 0;
                int b;
                do {
                    b = data[i++] & 0xFF;
                    remaining |= (b & 0x7F) << shift;
                    shift += 7;
                } while ((b & 0x80) != 0);
                int end = i + remaining;

                if ((first >> 4) == 3) { // PUBLISH
                    int qos = (first >> 1) & 0x03;
                    int topicLen = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
                    i += 2;
                    String topic = new String(data, i, topicLen, StandardCharsets.UTF_8);
                    i += topicLen;
                    if (qos > 0) {
                        i += 2; // packet id
                    }
                    // MQTT v5 has a properties-length varint before the payload; v3.1.1 (used on the
                    // restaurant topic) does not, and its JSON payload starts straight away with '{'.
                    if (data[i] != '{') {
                        int propLen = 0;
                        shift = 0;
                        do {
                            b = data[i++] & 0xFF;
                            propLen |= (b & 0x7F) << shift;
                            shift += 7;
                        } while ((b & 0x80) != 0);
                        i += propLen;
                    }
                    String payload = new String(data, i, end - i, StandardCharsets.UTF_8);
                    printMessage(role, topic, payload);
                }
                i = end;
            }
        } catch (RuntimeException e) {
            System.out.println("[MQTT][" + role + "] could not decode frame: " + e);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * One summary line per message ("MQTT ← [CUSTOMER] 025-030 · Preparing · "title""). With
     * -Dmqtt.full=true the full pretty-printed JSON is printed below it.
     */
    private static void printMessage(String role, String topic, String payload) {
        try {
            JsonNode root = MAPPER.readTree(payload);
            String arrow = ConsoleLogListener.UNICODE ? "←" : "<-";
            String sep = ConsoleLogListener.UNICODE ? " · " : " | ";
            System.out.println("[MQTT] " + arrow + " [" + role + "] " + root.path("orderNo").asText("?")
                    + sep + root.path("status").asText("?")
                    + sep + "\"" + root.path("title").asText("") + "\""
                    + ("RESTAURANT".equals(role) && !root.path("message").asText("").isEmpty()
                            ? " / \"" + root.path("message").asText() + "\"" : ""));
            if (Boolean.getBoolean("mqtt.full")) {
                String pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
                System.out.println(pretty.replaceAll("(?m)^", "    "));
            }
        } catch (Exception e) {
            System.out.println("[MQTT] <- [" + role + "] (unparsed message on " + topic + ")");
        }
    }
}
