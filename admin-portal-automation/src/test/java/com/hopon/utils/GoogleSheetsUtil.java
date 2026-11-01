package com.hopon.utils;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.hopon.config.ConfigReader;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scenario tab:    Feature | Scenario | Results | Is Automated? | Last Checked Time
 * Credentials tab: Name | Email | Password | Created Time | Restaurant ID | Restaurant Category | Restaurant Food
 */
public class GoogleSheetsUtil {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss");
    private static GoogleSheetsUtil instance;

    private final Sheets service;
    private final String spreadsheetId;
    private final String scenarioTab;
    private final String credentialsTab;

    private GoogleSheetsUtil() {
        this.spreadsheetId = ConfigReader.get("google.sheet.id");
        this.scenarioTab = ConfigReader.get("google.sheet.scenario.tab");
        this.credentialsTab = ConfigReader.get("google.sheet.credentials.tab");
        this.service = buildService();
    }

    public static synchronized GoogleSheetsUtil getInstance() {
        if (instance == null) {
            instance = new GoogleSheetsUtil();
        }
        return instance;
    }

    private Sheets buildService() {
        try (FileInputStream in = new FileInputStream(ConfigReader.get("google.credentials.path"))) {
            GoogleCredentials credentials = GoogleCredentials.fromStream(in)
                    .createScoped(Collections.singletonList(SheetsScopes.SPREADSHEETS));
            return new Sheets.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("HopOn-Food-Automation")
                    .build();
        } catch (IOException | GeneralSecurityException e) {
            throw new RuntimeException("Could not initialise Google Sheets service. Check google-credentials.json", e);
        }
    }

    // ---------- Scenario tab ----------

    /** Updates Results / Is Automated? / Last Checked Time for the matching row, or appends a new row. */
    public void recordResult(String feature, String scenario, String result) {
        List<List<Object>> rows = read(scenarioTab + "!A2:B");
        int sheetRow = -1;
        for (int i = 0; i < rows.size(); i++) {
            List<Object> r = rows.get(i);
            if (r.size() >= 2
                    && feature.equalsIgnoreCase(r.get(0).toString().trim())
                    && scenario.equalsIgnoreCase(r.get(1).toString().trim())) {
                sheetRow = i + 2; // data starts on row 2
                break;
            }
        }
        String now = LocalDateTime.now().format(TIME_FMT);
        if (sheetRow == -1) {
            append(scenarioTab + "!A:E", List.of(feature, scenario, result, "Yes", now));
        } else {
            write(scenarioTab + "!C" + sheetRow + ":E" + sheetRow, List.of(result, "Yes", now));
        }
    }

    // ---------- Credentials tab ----------

    public List<List<Object>> readCredentials() {
        return read(credentialsTab + "!A2:E");
    }

    // The row also holds the restaurant ID (column E)
    public void appendCredential(String name, String email, String password, String restaurantId) {
        append(credentialsTab + "!A:E",
                List.of(name, email, password, LocalDateTime.now().format(TIME_FMT), restaurantId));
    }

    /** Next free bot number, based on the automatedbot<N>@gmail.com emails already in the Credentials tab. */
    public int nextBotNumber() {
        Pattern pattern = Pattern.compile("^automatedbot(\\d+)@gmail\\.com$", Pattern.CASE_INSENSITIVE);
        int max = 0;
        for (List<Object> row : readCredentials()) {
            if (row.size() > 1) {
                Matcher m = pattern.matcher(row.get(1).toString().trim());
                if (m.matches()) {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                }
            }
        }
        return max + 1;
    }

    // One restaurant row from the Credentials tab. It also holds the sheet row
    public record RestaurantCredential(int row, String name, String email, String password, String restaurantId) { }

    // The last restaurant saved in the Credentials tab
    public RestaurantCredential lastCredential() {
        List<List<Object>> rows = readCredentials();
        for (int i = rows.size() - 1; i >= 0; i--) {
            List<Object> row = rows.get(i);
            if (!cell(row, 1).isEmpty() && !cell(row, 2).isEmpty()) {
                // the data starts on sheet row 2, so the sheet row is the index + 2
                return new RestaurantCredential(i + 2, cell(row, 0), cell(row, 1), cell(row, 2), cell(row, 4));
            }
        }
        throw new IllegalStateException("The Credentials tab has no restaurant yet");
    }

    private static String cell(List<Object> row, int index) {
        return index < row.size() ? row.get(index).toString().trim() : "";
    }

    // Next free category number, one more than the highest "Automated Food Burger <N>" in column F
    public int nextCategoryNumber() {
        Pattern pattern = Pattern.compile("Automated Food Burger (\\d+)", Pattern.CASE_INSENSITIVE);
        int max = 0;
        for (List<Object> row : read(credentialsTab + "!F2:F")) {
            if (!row.isEmpty()) {
                Matcher m = pattern.matcher(row.get(0).toString());
                while (m.find()) {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                }
            }
        }
        return max + 1;
    }

    // Saves the category next to the restaurant's row (column F)
    public void saveCategory(int row, String categoryName) {
        addToCredentialCell("F", row, categoryName);
    }

    // NEW: the last category saved on a restaurant's row (column F). Several are comma separated, so take the last one
    public String lastCategoryName(int row) {
        List<List<Object>> current = read(credentialsTab + "!F" + row);
        String cell = (current.isEmpty() || current.get(0).isEmpty()) ? "" : current.get(0).get(0).toString().trim();
        if (cell.isEmpty()) {
            throw new IllegalStateException("No category saved on sheet row " + row + ". Run the category test first");
        }
        String[] parts = cell.split(",");
        return parts[parts.length - 1].trim();
    }

    // NEW: next free food number, one more than the highest "Cheese Automated Burger <N>" in column G
    public int nextFoodNumber() {
        Pattern pattern = Pattern.compile("Cheese Automated Burger (\\d+)", Pattern.CASE_INSENSITIVE);
        int max = 0;
        for (List<Object> row : read(credentialsTab + "!G2:G")) {
            if (!row.isEmpty()) {
                Matcher m = pattern.matcher(row.get(0).toString());
                while (m.find()) {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                }
            }
        }
        return max + 1;
    }

    // NEW: saves the food next to the restaurant's row (column G)
    public void saveFood(int row, String foodName) {
        addToCredentialCell("G", row, foodName);
    }

    // Puts text in a column of a restaurant's row. If the cell already has something, the text is added after a comma.
    public void addToCredentialCell(String column, int row, String text) {
        String range = credentialsTab + "!" + column + row;
        List<List<Object>> current = read(range);
        String existing = (current.isEmpty() || current.get(0).isEmpty()) ? "" : current.get(0).get(0).toString().trim();
        write(range, List.of(existing.isEmpty() ? text : existing + ", " + text));
    }

    // ---------- Low-level helpers ----------

    private List<List<Object>> read(String range) {
        try {
            ValueRange res = service.spreadsheets().values().get(spreadsheetId, range).execute();
            return res.getValues() == null ? new ArrayList<>() : res.getValues();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + range, e);
        }
    }

    private void write(String range, List<Object> row) {
        try {
            ValueRange body = new ValueRange().setValues(List.of(row));
            service.spreadsheets().values().update(spreadsheetId, range, body)
                    .setValueInputOption("USER_ENTERED").execute();
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + range, e);
        }
    }

    private void append(String range, List<Object> row) {
        try {
            ValueRange body = new ValueRange().setValues(List.of(row));
            service.spreadsheets().values().append(spreadsheetId, range, body)
                    .setValueInputOption("USER_ENTERED")
                    .setInsertDataOption("INSERT_ROWS").execute();
        } catch (IOException e) {
            throw new RuntimeException("Failed to append to " + range, e);
        }
    }
}