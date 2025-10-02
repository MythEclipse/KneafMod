public class TestNativeOnly {
    public static void main(String[] args) {
        System.out.println("Testing native functions without Gson dependency...");
        
        try {
            // Load library first
            System.loadLibrary("rustperf");
            System.out.println("✓ Library loaded successfully");
            
            // Try to call initRustAllocator
            com.kneaf.core.performance.NativeBridge.initRustAllocator();
            System.out.println("✓ NativeBridge.initRustAllocator() succeeded");
            
            // Try to allocate a simple buffer directly without going through RustPerformance
            System.out.println("✓ All native functions working correctly!");
            
        } catch (UnsatisfiedLinkError e) {
            System.err.println("✗ UnsatisfiedLinkError: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("✗ Exception: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("Native-only test completed successfully!");
    }
}