package com.kneaf.core;

public class RustVectorLibraryTest {
    public static void main(String[] args) {
        System.out.println("Testing RustVectorLibrary functions...");
        boolean result = RustVectorLibrary.testAllFunctions();
        System.out.println("Test result: " + (result ? "PASSED" : "FAILED"));
        if (!result) {
            System.exit(1);
        }
    }
}