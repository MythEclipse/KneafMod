package com.kneaf.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test class that shows CLEAR PASS/FAIL COUNTS directly
 * Uses existing RustVectorLibrary.testAllFunctions() which already works
 */
public class ShowTestCounts {
    private static int totalTests = 0;
    private static int passedTests = 0;
    private static int failedTests = 0;

    @Test
    public void testRustVectorLibraryAllFunctions() {
        totalTests++;
        System.out.println("🧪 Running: testRustVectorLibraryAllFunctions");
        
        try {
            boolean allTestsPassed = RustVectorLibrary.testAllFunctions();
            
            if (allTestsPassed) {
                passedTests++;
                System.out.println("✅ PASSED: All Rust vector library functions");
            } else {
                failedTests++;
                System.out.println("❌ FAILED: Some Rust vector library functions");
            }
        } catch (Exception e) {
            failedTests++;
            System.out.println("❌ FAILED: Exception in Rust vector library test: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    public void testLibraryLoading() {
        totalTests++;
        System.out.println("\n🧪 Running: testLibraryLoading");
        
        try {
            boolean libraryLoaded = RustVectorLibrary.isLibraryLoaded();
            
            if (libraryLoaded) {
                passedTests++;
                System.out.println("✅ PASSED: Native library loading");
            } else {
                failedTests++;
                System.out.println("❌ FAILED: Native library not loaded");
            }
        } catch (Exception e) {
            failedTests++;
            System.out.println("❌ FAILED: Exception in library loading test: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    public void testVectorAddition() {
        totalTests++;
        System.out.println("\n🧪 Running: testVectorAddition");
        
        try {
            float[] vecA = {1.0f, 2.0f, 3.0f};
            float[] vecB = {4.0f, 5.0f, 6.0f};
            float[] result = RustVectorLibrary.vectorAddNalgebra(vecA, vecB);
            
            // Verify result is correct
            boolean additionCorrect = result[0] == 5.0f && result[1] == 7.0f && result[2] == 9.0f;
            
            if (additionCorrect) {
                passedTests++;
                System.out.println("✅ PASSED: Vector addition test");
            } else {
                failedTests++;
                System.out.println("❌ FAILED: Vector addition test - expected [5.0, 7.0, 9.0], got [" + 
                    result[0] + ", " + result[1] + ", " + result[2] + "]");
            }
        } catch (Exception e) {
            failedTests++;
            System.out.println("❌ FAILED: Exception in vector addition test: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    public void testVectorDotProduct() {
        totalTests++;
        System.out.println("\n🧪 Running: testVectorDotProduct");
        
        try {
            float[] vecA = {1.0f, 2.0f, 3.0f};
            float[] vecB = {4.0f, 5.0f, 6.0f};
            float result = RustVectorLibrary.vectorDotGlam(vecA, vecB);
            
            // Verify result is correct (1*4 + 2*5 + 3*6 = 32)
            boolean dotCorrect = Math.abs(result - 32.0f) < 0.001f;
            
            if (dotCorrect) {
                passedTests++;
                System.out.println("✅ PASSED: Vector dot product test");
            } else {
                failedTests++;
                System.out.println("❌ FAILED: Vector dot product test - expected 32.0, got " + result);
            }
        } catch (Exception e) {
            failedTests++;
            System.out.println("❌ FAILED: Exception in vector dot product test: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // This method will run after all tests and show the summary
    public static void showSummary() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("🧪 FINAL TEST RESULTS SUMMARY");
        System.out.println("=".repeat(70));
        System.out.println(String.format("Total tests run:      %d", totalTests));
        System.out.println(String.format("Tests PASSED:         %d", passedTests));
        System.out.println(String.format("Tests FAILED:         %d", failedTests));
        System.out.println(String.format("Pass rate:            %.1f%%", 
                totalTests > 0 ? (passedTests * 100.0 / totalTests) : 0));
        System.out.println("=".repeat(70));
        
        // Final status message
        if (failedTests == 0) {
            System.out.println("\n🎉 ALL TESTS PASSED SUCCESSFULLY!");
        } else {
            System.out.println("\n⚠️  SOME TESTS FAILED - CHECK DETAILS ABOVE!");
        }
    }

    // Main method to run tests and show results (can be run directly)
    public static void main(String[] args) {
        System.out.println("🔍 Starting Rust Vector Library Tests - Clear Results Display");
        System.out.println("=".repeat(70));
        
        // Create instance and run tests
        ShowTestCounts tester = new ShowTestCounts();
        
        try {
            tester.testRustVectorLibraryAllFunctions();
            tester.testLibraryLoading();
            tester.testVectorAddition();
            tester.testVectorDotProduct();
            
            // Show summary
            showSummary();
            
        } catch (Exception e) {
            System.out.println("❌ FATAL ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }
}