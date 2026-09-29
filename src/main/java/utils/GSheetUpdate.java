package utils;

/**
 * Intentionally unimplemented. CSV files under DataSource/ cover current test-data
 * needs; this stub exists only in case a shared reporting Google Sheet becomes an
 * actual requirement later (e.g. pushing live run results somewhere non-engineers
 * can see). Wiring this up needs a Google service-account credentials file and the
 * google-api-services-sheets dependency — neither is set up yet.
 */
public class GSheetUpdate {

    private GSheetUpdate() {
    }

    public static void appendRow(String spreadsheetId, String sheetRange, Object[] rowValues) {
        throw new UnsupportedOperationException(
                "GSheetUpdate is not implemented — see class Javadoc in GSheetUpdate.java");
    }
}
