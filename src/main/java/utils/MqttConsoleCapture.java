package utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prints the MQTT pushes a browser page receives, tagged by role, one line each, e.g.
 * {@code [MQTT] <- [CUSTOMER] 024-D74 | Preparing | "Preparing your food"}.
 *
 * Hooks the page's MQTT WebSocket (wss://...mqtt) and decodes each PUBLISH packet the
 * broker pushes to that page, so what is printed is the real payload the app received.
 * Called from the page-object base classes, so every customer/restaurant page is covered
 * with no per-test code.
 *
 * Playwright's sync Java API only delivers WebSocket events while a Playwright call is
 * running, so a test that closes the browser right after its last API call would lose
 * the final pushes (e.g. "Completed"). {@link #awaitStatus} pumps events until the
 * expected push has been printed for every connected role.
 */
public final class MqttConsoleCapture {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Attached pages -> role. */
    private static final Map<Page, String> ATTACHED = Collections.synchronizedMap(new WeakHashMap<>());
    /** Pages that actually opened an MQTT WebSocket. */
    private static final Set<Page> CONNECTED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    /** "ROLE|orderNo|status" for every message printed. */
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    /** "ROLE|id|timestamp" - the same push redelivered (QoS 2 retry / reconnect) is printed once. */
    private static final Set<String> PRINTED = ConcurrentHashMap.newKeySet();

    private MqttConsoleCapture() {
    }

    public static void attach(Page page, String role) {
        if (ATTACHED.putIfAbsent(page, role) != null) {
            return;
        }
        page.onWebSocket(ws -> {
            if (!ws.url().toLowerCase().contains("mqtt")) {
                return;
            }
            CONNECTED.add(page);
            ByteArrayOutputStream pending = new ByteArrayOutputStream();
            ws.onFrameReceived(frame -> {
                synchronized (pending) {
                    byte[] bytes = frame.binary();
                    if (bytes != null) {
                        pending.write(bytes, 0, bytes.length);
                    }
                    byte[] rest = printCompletePackets(role, pending.toByteArray());
                    pending.reset();
                    pending.write(rest, 0, rest.length);
                }
            });
        });
    }

    /**
     * Blocks (up to timeoutSeconds) while pumping Playwright events until every role that
     * has a live MQTT connection has printed a push with this orderNo and status. Prints a
     * one-line note for any role that never got it.
     */
    public static void awaitStatus(String orderNo, String status, int timeoutSeconds) {
        List<Page> live = new ArrayList<>();
        List<String> roles = new ArrayList<>();
        synchronized (ATTACHED) {
            for (Map.Entry<Page, String> e : ATTACHED.entrySet()) {
                if (!e.getKey().isClosed() && CONNECTED.contains(e.getKey()) && !roles.contains(e.getValue())) {
                    live.add(e.getKey());
                    roles.add(e.getValue());
                }
            }
        }
        if (live.isEmpty()) {
            return;
        }
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline && !roles.stream().allMatch(r -> seen(r, orderNo, status))) {
            live.get(0).waitForTimeout(300);
        }
        for (String role : roles) {
            if (!seen(role, orderNo, status)) {
                System.out.println("[MQTT] (no " + status + " push seen for [" + role + "] " + orderNo
                        + " within " + timeoutSeconds + "s)");
            }
        }
    }

    private static boolean seen(String role, String orderNo, String status) {
        return SEEN.contains(role + "|" + orderNo + "|" + status);
    }

    /**
     * Walks the buffered bytes, prints every complete PUBLISH packet, and returns the
     * trailing bytes of an incomplete packet (kept until the next frame completes it).
     */
    private static byte[] printCompletePackets(String role, byte[] data) {
        int i = 0;
        while (i < data.length) {
            int start = i;
            int first = data[i++] & 0xFF;
            int remaining = 0;
            int shift = 0;
            boolean lengthComplete = false;
            while (i < data.length) {
                int b = data[i++] & 0xFF;
                remaining |= (b & 0x7F) << shift;
                shift += 7;
                if ((b & 0x80) == 0) {
                    lengthComplete = true;
                    break;
                }
            }
            if (!lengthComplete || i + remaining > data.length) {
                return java.util.Arrays.copyOfRange(data, start, data.length);
            }
            int end = i + remaining;
            if ((first >> 4) == 3) { // PUBLISH
                try {
                    printPublish(role, first, data, i, end);
                } catch (RuntimeException e) {
                    System.out.println("[MQTT][" + role + "] could not decode packet: " + e);
                }
            }
            i = end;
        }
        return new byte[0];
    }

    private static void printPublish(String role, int first, byte[] data, int i, int end) {
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
            int shift = 0;
            int b;
            do {
                b = data[i++] & 0xFF;
                propLen |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            i += propLen;
        }
        printMessage(role, topic, new String(data, i, end - i, StandardCharsets.UTF_8));
    }

    /**
     * One summary line per message ("[MQTT] <- [CUSTOMER] 025-030 | Preparing | "title""). Restaurant
     * lines also show the message text. With -Dmqtt.full=true the full pretty-printed JSON is
     * printed below it.
     */
    private static void printMessage(String role, String topic, String payload) {
        try {
            JsonNode root = MAPPER.readTree(payload);
            String orderNo = root.path("orderNo").asText("?");
            String status = root.path("status").asText("?");
            if (!PRINTED.add(role + "|" + root.path("id").asText() + "|" + root.path("timestamp").asText() + "|" + status)) {
                return;
            }
            String arrow = ConsoleLogListener.UNICODE ? "←" : "<-";
            String sep = ConsoleLogListener.UNICODE ? " · " : " | ";
            System.out.println("[MQTT] " + arrow + " [" + role + "] " + orderNo
                    + sep + status
                    + sep + "\"" + root.path("title").asText("") + "\""
                    + ("RESTAURANT".equals(role) && !root.path("message").asText("").isEmpty()
                            ? " / \"" + root.path("message").asText() + "\"" : ""));
            SEEN.add(role + "|" + orderNo + "|" + status);
            if (Boolean.getBoolean("mqtt.full")) {
                String pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
                System.out.println(pretty.replaceAll("(?m)^", "    "));
            }
        } catch (Exception e) {
            System.out.println("[MQTT] <- [" + role + "] (unparsed message on " + topic + ")");
        }
    }
}
