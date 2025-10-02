public class TestFloatBuffer {
    public static void main(String[] args) {
        System.out.println("Testing generateFloatBufferNative function...");
        
        try {
            // Load library first
            System.loadLibrary("rustperf");
            System.out.println("✓ Library loaded successfully");
            
            // Try to call initRustAllocator
            com.kneaf.core.performance.NativeBridge.initRustAllocator();
            System.out.println("✓ NativeBridge.initRustAllocator() succeeded");
            
            // Try to allocate a float buffer
            java.nio.ByteBuffer buffer = com.kneaf.core.performance.RustPerformance.generateFloatBufferNative(10, 10);
            if (buffer != null) {
                System.out.println("✓ RustPerformance.generateFloatBufferNative() succeeded!");
                System.out.println("  Buffer capacity: " + buffer.capacity() + " bytes");
                System.out.println("  Buffer is direct: " + buffer.isDirect());
                System.out.println("  Buffer order: " + buffer.order());
                
                // Test writing and reading float
                buffer.rewind();
                buffer.asFloatBuffer().put(0, 3.14f);
                float value = buffer.asFloatBuffer().get(0);
                System.out.println("  Test float value: " + value);
                
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
        
        System.out.println("Float buffer test completed.");
    }
}