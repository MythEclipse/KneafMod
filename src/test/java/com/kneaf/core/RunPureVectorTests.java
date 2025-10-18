package com.kneaf.core;

import org.junit.platform.console.ConsoleLauncher;
import java.util.Arrays;

/**
 * Simple runner for PureVectorOperationsTest that doesn't require Gradle
 * Can be run directly from IDE or command line with:
 * java -cp target/classes:target/test-classes com.kneaf.core.RunPureVectorTests
 */
public class RunPureVectorTests {
    public static void main(String[] args) {
        Result result = JUnitCore.runClasses(PureVectorOperationsTest.class);
        
        System.out.println("Pure Vector Operations Test Results:");
        System.out.println("Tests run: " + result.getRunCount());
        System.out.println("Tests failed: " + result.getFailureCount());
        System.out.println("Tests ignored: " + result.getIgnoreCount());
        
        for (Failure failure : result.getFailures()) {
            System.out.println("\nTest failed: " + failure.getTestHeader());
            System.out.println("Reason: " + failure.getTrace());
        }
        
        if (result.wasSuccessful()) {
            System.out.println("\n✅ All tests passed!");
        } else {
            System.out.println("\n❌ Some tests failed!");
            System.exit(1);
        }
    }
}