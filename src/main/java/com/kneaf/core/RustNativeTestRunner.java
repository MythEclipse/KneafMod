package com.kneaf.core;

/**
 * Standalone Test Runner for Rust Native JNI Methods
 * 
 * Can be run as a standalone Java application to test all JNI bindings
 * without starting the full Minecraft server.
 * 
 * Usage:
 *   java -cp build/classes/java/main:build/resources/main com.kneaf.core.RustNativeTestRunner
 */
public class RustNativeTestRunner {
    
    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║         KneafMod Rust Native JNI Test Runner                ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        
        // Set up logging
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "INFO");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss");
        
        try {
            // Run all tests
            RustNativeTest.runAllTests();
            
            System.out.println();
            System.out.println("══════════════════════════════════════════════════════════════");
            System.out.println("Test execution completed successfully!");
            System.out.println("══════════════════════════════════════════════════════════════");
            
            System.exit(0);
            
        } catch (Exception e) {
            System.err.println();
            System.err.println("══════════════════════════════════════════════════════════════");
            System.err.println("FATAL ERROR during test execution:");
            e.printStackTrace();
            System.err.println("══════════════════════════════════════════════════════════════");
            
            System.exit(1);
        }
    }
}
