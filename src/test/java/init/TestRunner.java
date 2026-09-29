package init;

import org.testng.TestNG;

import java.util.Collections;

/**
 * Programmatic suite runner — alternative to `mvn test` for running a specific
 * suite XML directly via `java`, e.g. from a custom CI step.
 *
 * Usage:
 *   java -cp target/classes:target/test-classes:<deps> init.TestRunner -Dsuite=test-suites/smoke.xml
 *
 * If -Dsuite is not provided, defaults to test-suites/testng.xml.
 */
public class TestRunner {

    public static void main(String[] args) {
        String suiteFile = System.getProperty("suite", "test-suites/testng.xml");

        TestNG testng = new TestNG();
        testng.setTestSuites(Collections.singletonList(suiteFile));
        testng.setUseDefaultListeners(true);
        testng.setOutputDirectory("target/surefire-reports");

        testng.run();

        if (testng.hasFailure()) {
            System.exit(1);
        }
    }
}
