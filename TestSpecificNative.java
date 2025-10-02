public class TestSpecificNative {
    public static void main(String[] args) {
        System.out.println("Testing specific native functions...");
        
        try {
            // Load library first
            System.loadLibrary("rustperf");
            System.out.println("✓ Library loaded successfully");
            
            // Try to call initRustAllocator
            com.kneaf.core.performance.NativeBridge.initRustAllocator();
            System.out.println("✓ NativeBridge.initRustAllocator() succeeded");
            
            // Try to allocate a simple buffer
            java.nio.ByteBuffer buffer = com.kneaf.core.performance.RustPerformance.generateFloatBufferNative(10, 10);
            if (buffer != null) {
                System.out.println("✓ RustPerformance.generateFloatBufferNative() succeeded, buffer capacity: " + buffer.capacity());
            } else {
                System.out.println("✗ RustPerformance.generateFloatBufferNative() returned null");
            }
            
        } catch (UnsatisfiedLinkError e) {
            System.err.println("✗ UnsatisfiedLinkError: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("✗ Exception: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("Specific native test completed.");
    }
}