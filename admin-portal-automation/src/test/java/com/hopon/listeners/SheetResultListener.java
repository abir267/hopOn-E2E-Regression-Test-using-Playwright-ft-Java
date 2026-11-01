package com.hopon.listeners;

import com.hopon.utils.GoogleSheetsUtil;
import com.hopon.utils.SheetScenario;
import org.testng.ITestListener;
import org.testng.ITestResult;

/** Writes Pass/Fail to the Scenario tab for every test annotated with @SheetScenario. */
public class SheetResultListener implements ITestListener {

    @Override
    public void onTestSuccess(ITestResult result) {
        record(result, "Pass");
    }

    @Override
    public void onTestFailure(ITestResult result) {
        record(result, "Fail");
    }

    private void record(ITestResult result, String status) {
        SheetScenario meta = result.getMethod().getConstructorOrMethod().getMethod()
                .getAnnotation(SheetScenario.class);
        if (meta == null) {
            return;
        }
        try {
            GoogleSheetsUtil.getInstance().recordResult(meta.feature(), meta.scenario(), status);
        } catch (Exception e) {
            System.err.println("Could not write result to Google Sheet: " + e.getMessage());
        }
    }
}
