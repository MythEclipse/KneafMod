import com.kneaf.core.VectorOperationsTest;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.engine.discovery.DiscoverySelectors;

import java.util.List;

public class StandaloneTestRunner {
    public static void main(String[] args) {
        try {
            // Create a launcher
            Launcher launcher = org.junit.platform.launcher.core.LauncherFactory.create();
            
            // Create a request to run only VectorOperationsTest
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClass(VectorOperationsTest.class))
                    .build();
            
            // Create a listener to capture test results
            SummaryGeneratingListener listener = new SummaryGeneratingListener();
            
            // Run the tests
            launcher.execute(request, listener);
            
            // Get and print the summary
            TestExecutionSummary summary = listener.getSummary();
            System.out.println("Test Results:");
            System.out.println("Total tests: " + summary.getTotalTests());
            System.out.println("Failed tests: " + summary.getFailedTests().size());
            System.out.println("Aborted tests: " + summary.getAbortedTests().size());
            System.out.println("Skipped tests: " + summary.getSkippedTests().size());
            System.out.println("Passed tests: " + summary.getPassedTests().size());
            
            // Print details of failed tests
            List<TestIdentifier> failedTests = summary.getFailedTests();
            if (!failedTests.isEmpty()) {
                System.out.println("\nFailed Tests:");
                for (TestIdentifier testId : failedTests) {
                    System.out.println("- " + testId.getDisplayName());
                }
            }
            
        } catch (Exception e) {
            System.err.println("Error running tests: " + e.getMessage());
            e.printStackTrace();
        }
    }
}