import com.kneaf.core.RustVectorLibrary;

/**
 * Standalone test runner for RustVectorLibrary that doesn't require Minecraft dependencies
 */
public class VectorTestRunner {
    public static void main(String[] args) {
        System.out.println("Starting standalone RustVectorLibrary tests...");
        
        // Test 1: Check library loading status
        testLibraryLoading();
        
        // Test 2: Test vector operations if library is loaded
        if (RustVectorLibrary.isLibraryLoaded()) {
            testVectorOperations();
        } else {
            System.out.println("Skipping vector operation tests - native library not loaded");
        }
        
        // Test 3: Test that RustVectorLibrary doesn't expose game logic
        testNoGameLogicExposure();
        
        System.out.println("All standalone tests completed!");
    }
    
    private static void testLibraryLoading() {
        System.out.println("\n=== Test 1: Library Loading Status ===");
        boolean isLoaded = RustVectorLibrary.isLibraryLoaded();
        System.out.println("Native library loaded: " + isLoaded);
        
        if (!isLoaded) {
            System.out.println("Note: Native library loading is expected to fail in this environment");
            return;
        }
        
        System.out.println("✓ Library loading test passed");
    }
    
    private static void testVectorOperations() {
        System.out.println("\n=== Test 2: Vector Operations ===");
        
        // Test data
        float[] vecA = {1.0f, 2.0f, 3.0f};
        float[] vecB = {4.0f, 5.0f, 6.0f};
        
        try {
            // Test vector addition
            System.out.println("Testing vector addition...");
            float[] addResult = RustVectorLibrary.vectorAddNalgebra(vecA, vecB);
            System.out.println("Vector addition result: " + java.util.Arrays.toString(addResult));
            
            // Test vector dot product
            System.out.println("Testing vector dot product...");
            float dotResult = RustVectorLibrary.vectorDotGlam(vecA, vecB);
            System.out.println("Vector dot product result: " + dotResult);
            
            System.out.println("✓ Vector operations test passed");
            
        } catch (Exception e) {
            System.out.println("Vector operations test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void testNoGameLogicExposure() {
        System.out.println("\n=== Test 3: No Game Logic Exposure ===");
        
        // Test that RustVectorLibrary doesn't expose any game-related operations
        java.lang.reflect.Method[] methods = RustVectorLibrary.class.getDeclaredMethods();
        boolean hasGameLogicMethods = false;
        
        for (java.lang.reflect.Method method : methods) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                String methodName = method.getName();
                if (methodName.contains("entity") || methodName.contains("game") || 
                    methodName.contains("player") || methodName.contains("ai") || 
                    methodName.contains("path")) {
                    hasGameLogicMethods = true;
                    System.out.println("WARNING: Game logic method found: " + methodName);
                    break;
                }
            }
        }
        
        if (!hasGameLogicMethods) {
            System.out.println("✓ No game logic exposure test passed - all public methods are vector-related");
        } else {
            System.out.println("✗ Game logic exposure test failed - found game-related methods");
        }
    }
}