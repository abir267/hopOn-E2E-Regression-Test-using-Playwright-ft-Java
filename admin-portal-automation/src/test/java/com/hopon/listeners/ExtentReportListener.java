package com.hopon.listeners;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.reporter.ExtentSparkReporter;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ExtentReportListener implements ITestListener {

    private static ExtentReports extent;
    private static final ThreadLocal<ExtentTest> currentTest = new ThreadLocal<>();
    private static String reportPath;

    @Override
    public synchronized void onStart(ITestContext context) {
        if (extent != null) {
            return;
        }
        new File("reports").mkdirs();
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        reportPath = "reports/E2E-Report-" + time + ".html";

        ExtentSparkReporter spark = new ExtentSparkReporter(reportPath);
        spark.config().setDocumentTitle("HopOn Food Automation");
        spark.config().setReportName("Restaurant End To End");

        extent = new ExtentReports();
        extent.attachReporter(spark);
        extent.setSystemInfo("Portal", "dev-admin.hoponbd.com");
        extent.setSystemInfo("Browser", "Chromium (Playwright)");
    }

    @Override
    public void onTestStart(ITestResult result) {
        String name = result.getTestClass().getRealClass().getSimpleName() + " > " + result.getMethod().getMethodName();
        currentTest.set(extent.createTest(name));
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        currentTest.get().pass("Test passed");
    }

    @Override
    public void onTestFailure(ITestResult result) {
        currentTest.get().fail(result.getThrowable());
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        currentTest.get().skip("Skipped: " + (result.getThrowable() != null ? result.getThrowable().getMessage() : ""));
    }

    @Override
    public synchronized void onFinish(ITestContext context) {
        if (extent != null) {
            extent.flush();
            System.out.println("REPORT: " + new File(reportPath).getAbsolutePath());
        }
    }
}