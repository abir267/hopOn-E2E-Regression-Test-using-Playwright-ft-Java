package utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class DriverAppLog implements AutoCloseable {

    private static final String ROLE = "DRIVER";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern RAW = Pattern.compile("TRIP_DATA\\(\\s*\\d+\\): \\[MQTT-RAW\\] (\\S+) \\S+ (\\{.*\\})\\s*$");
    private static final Pattern PUBLISHED = Pattern.compile("MqttKit\\s*\\(\\s*\\d+\\): MQTT \\S+ published topic=(\\S+)");
    /** A line of the HTTP logging box: "...HTTP( 8476): ║ 503 Service Unavailable". */
    private static final Pattern HTTP_BOX = Pattern.compile("HTTP\\(\\s*\\d+\\): ║ (.*)$");
    private static final Pattern HTTP_STATUS = Pattern.compile("^(\\d{3})\\b.*");
    private static final Pattern HTTP_REQUEST = Pattern.compile("^(GET|POST|PUT|PATCH|DELETE) (\\S+)");

    private final Process logcat;
    private final Map<String, List<JsonNode>> payloadsByOrder = new ConcurrentHashMap<>();
    /** HTTP box state: after a "RESPONSE" line comes the status line, then the "METHOD url" line. */
    private boolean inResponse;
    private int responseStatus;

    private DriverAppLog(Process logcat) {
        this.logcat = logcat;
        Thread reader = new Thread(this::readLines, "driver-app-log");
        reader.setDaemon(true);
        reader.start();
    }

    /** Starts listening from now on. Never fails the test: without a log, the driver lines are simply absent. */
    public static DriverAppLog start(AdbDevice device, String appPackage) {
        try {
            String pid = device.pidOf(appPackage);
            return new DriverAppLog(device.startLogcat(pid, "TRIP_DATA:I", "MqttKit:D"));
        } catch (IOException | RuntimeException e) {
            System.out.println("[MQTT] (driver app log not available: " + e.getMessage() + ")");
            return new DriverAppLog(null);
        }
    }

    private void readLines() {
        if (logcat == null) {
            return;
        }
        try (BufferedReader in = new BufferedReader(new InputStreamReader(logcat.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                handle(line);
            }
        } catch (IOException ignored) {
            // the process was destroyed in close()
        }
    }

    private void handle(String line) {
        Matcher raw = RAW.matcher(line);
        if (raw.find()) {
            String topic = raw.group(1);
            String payload = raw.group(2);
            try {
                JsonNode json = MAPPER.readTree(payload);
                String orderNo = json.path("orderNo").asText("");
                if (!orderNo.isEmpty()) {
                    payloadsByOrder.computeIfAbsent(orderNo, k -> new CopyOnWriteArrayList<>()).add(json);
                }
            } catch (IOException ignored) {
                // printMessage below reports it as unparsed
            }
            MqttConsoleCapture.printMessage(ROLE, topic, payload);
            return;
        }
        Matcher published = PUBLISHED.matcher(line);
        if (published.find()) {
            if (!published.group(1).startsWith("location/")) {
                System.out.println("[MQTT] " + (ConsoleLogListener.UNICODE ? "→" : "->") + " [" + ROLE + "] published " + published.group(1));
            }
            return;
        }
        Matcher box = HTTP_BOX.matcher(line);
        if (box.find()) {
            handleHttp(box.group(1).trim());
        }
    }

    private void handleHttp(String content) {
        if (content.startsWith("RESPONSE")) {
            inResponse = true;
            responseStatus = 0;
            return;
        }
        if (!inResponse) {
            return;
        }
        if (responseStatus == 0) {
            Matcher status = HTTP_STATUS.matcher(content);
            if (status.matches()) {
                responseStatus = Integer.parseInt(status.group(1));
            }
            return;
        }
        Matcher request = HTTP_REQUEST.matcher(content);
        if (request.find()) {
            if (responseStatus >= 400) {
                System.out.println("[BUG][DRIVER_APP] " + responseStatus + " " + request.group(1) + " " + request.group(2));
                BugReport.http("DRIVER_APP", responseStatus, request.group(1), request.group(2));
            }
            inResponse = false;
        }
    }

    /** Waits until the driver app has received a push for this order with this status. */
    public boolean awaitStatus(String orderNo, String status, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        do {
            if (MqttConsoleCapture.sawStatus(ROLE, orderNo, status)) {
                return true;
            }
            AdbDevice.sleep(300);
        } while (System.currentTimeMillis() < deadline);
        return MqttConsoleCapture.sawStatus(ROLE, orderNo, status);
    }

    /** The first non-empty value at this JSON pointer (e.g. "/deliveryInstruction") in any push the driver got for the order, else null. */
    public String firstValue(String orderNo, String jsonPointer) {
        for (JsonNode payload : new ArrayList<>(payloadsByOrder.getOrDefault(orderNo, List.of()))) {
            JsonNode node = payload.at(jsonPointer);
            if (!node.isMissingNode() && !node.isNull() && !node.asText("").isEmpty()) {
                return node.asText();
            }
        }
        return null;
    }

    @Override
    public void close() {
        if (logcat != null) {
            logcat.destroy();
        }
    }
}
