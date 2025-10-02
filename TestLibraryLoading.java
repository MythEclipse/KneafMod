import com.kneaf.core.performance.NativeBridge;

public class TestLibraryLoading {
    public static void main(String[] args) {
        System.out.println("Testing native library loading...");
        
        try {
            // Try to load the library directly
            System.loadLibrary("rustperf");
            System.out.println("✓ Library loaded successfully via System.loadLibrary()");
        } catch (UnsatisfiedLinkError e) {
            System.err.println("✗ Failed to load library via System.loadLibrary(): " + e.getMessage());
        }
        
        try {
            // Try to access RustPerformance
            Class.forName("com.kneaf.core.performance.RustPerformance");
            System.out.println("✓ RustPerformance class loaded successfully");
        } catch (ClassNotFoundException e) {
            System.err.println("✗ RustPerformance class not found: " + e.getMessage());
        }
        
        try {
            // Try to use NativeBridge static method
            NativeBridge.initRustAllocator();
            System.out.println("✓ NativeBridge.initRustAllocator() called successfully");
        } catch (Exception e) {
            System.err.println("✗ Failed to call NativeBridge.initRustAllocator(): " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("Library loading test completed.");
    }
}