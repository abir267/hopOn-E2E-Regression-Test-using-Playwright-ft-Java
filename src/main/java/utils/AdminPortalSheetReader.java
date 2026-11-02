package utils;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only lookup into the SAME "HopOn Food- Automation data" Google Sheet the
 * admin-portal-automation module (sibling Maven project, ../admin-portal-automation)
 * writes to when it creates a restaurant, then a category, then a food item - see
 * that module's com.hopon.utils.GoogleSheetsUtil. Credentials tab layout:
 * Name | Email | Password | Created Time | Restaurant ID | Restaurant Category | Restaurant Food
 * (category/food cells can hold several comma-separated values across repeated runs;
 * the LAST one is the most recently created).
 *
 * This class only reads - admin-portal-automation owns writing to that sheet, and its
 * code is deliberately left untouched. The point is letting an end-to-end test here
 * order from whatever restaurant the admin side most recently built.
 */
public class AdminPortalSheetReader {

    private final Sheets service;
    private final String spreadsheetId;
    private final String credentialsTab;

    public AdminPortalSheetReader() {
        this.spreadsheetId = ConfigReader.googleSheetId();
        this.credentialsTab = ConfigReader.googleSheetCredentialsTab();
        this.service = buildService();
    }

    private Sheets buildService() {
        try (FileInputStream in = new FileInputStream(ConfigReader.googleCredentialsPath())) {
            GoogleCredentials credentials = GoogleCredentials.fromStream(in)
                    .createScoped(Collections.singletonList(SheetsScopes.SPREADSHEETS_READONLY));
            return new Sheets.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("HopOn-Food-Automation-Reader")
                    .build();
        } catch (IOException | GeneralSecurityException e) {
            throw new RuntimeException("Could not initialise Google Sheets service. Check "
                    + ConfigReader.googleCredentialsPath(), e);
        }
    }

    /** One restaurant row, with its last-saved category and food item name, plus the 1-based sheet row. */
    public record AdminCreatedRestaurant(int row, String name, String email, String password,
                                          String createdTime, String restaurantId,
                                          String category, String food) {
    }

    /** The most recently created restaurant that already has a category AND a food item saved. */
    public AdminCreatedRestaurant lastCreatedRestaurant() {
        List<List<Object>> rows = read(credentialsTab + "!A2:G");
        for (int i = rows.size() - 1; i >= 0; i--) {
            List<Object> row = rows.get(i);
            String name = cell(row, 0);
            String email = cell(row, 1);
            if (name.isEmpty() || email.isEmpty()) {
                continue;
            }
            String category = lastCommaValue(cell(row, 5));
            String food = lastCommaValue(cell(row, 6));
            if (category.isEmpty() || food.isEmpty()) {
                throw new IllegalStateException("Sheet row " + (i + 2) + " (" + name
                        + ") has no category/food saved yet - run the admin category/food creation first");
            }
            return new AdminCreatedRestaurant(i + 2, name, email, cell(row, 2), cell(row, 3), cell(row, 4), category, food);
        }
        throw new IllegalStateException("The Credentials tab has no restaurant yet");
    }

    private static String lastCommaValue(String cell) {
        if (cell.isEmpty()) {
            return "";
        }
        String[] parts = cell.split(",");
        return parts[parts.length - 1].trim();
    }

    private static String cell(List<Object> row, int index) {
        return index < row.size() && row.get(index) != null ? row.get(index).toString().trim() : "";
    }

    private List<List<Object>> read(String range) {
        try {
            ValueRange res = service.spreadsheets().values().get(spreadsheetId, range).execute();
            return res.getValues() == null ? new ArrayList<>() : res.getValues();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + range, e);
        }
    }
}
