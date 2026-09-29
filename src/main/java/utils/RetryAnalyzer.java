package utils;

import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

/**
 * Retries a failed @Test up to MAX_RETRY times before letting it stay failed.
 *
 * Bumped from 2 to 4 (2026-09-29): under heavy same-session usage (many real
 * logins in a short window, as this whole test suite does), OTP-box render
 * timeouts on dev-food.hoponbd.com got noticeably more frequent - CONFIRMED
 * the login mechanics themselves are fine (a fresh manual run completes in
 * well under half the default timeout), so this is transient backend/network
 * flakiness worth absorbing with more attempts rather than a root-cause bug
 * in LoginPage. Each retry uses a fresh random phone (TestDataGenerator),
 * so retries don't just repeat the exact same failure.
 */
public class RetryAnalyzer implements IRetryAnalyzer {

    private static final int MAX_RETRY = 4;
    private int retryCount = 0;

    @Override
    public boolean retry(ITestResult result) {
        if (retryCount < MAX_RETRY) {
            retryCount++;
            System.out.printf("Retrying '%s' (attempt %d/%d)%n",
                    result.getMethod().getMethodName(), retryCount, MAX_RETRY);
            return true;
        }
        return false;
    }
}
