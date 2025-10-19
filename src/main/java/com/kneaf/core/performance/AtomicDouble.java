package com.kneaf.core.performance;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe double value implementation using AtomicLong for storage.
 * Provides atomic operations for double values without using synchronized blocks.
 */
public final class AtomicDouble {
    private final AtomicLong value;
    
    public AtomicDouble(double initialValue) {
        this.value = new AtomicLong(Double.doubleToRawLongBits(initialValue));
    }
    
    public AtomicDouble() {
        this(0.0);
    }
    
    public void set(double newValue) {
        value.set(Double.doubleToRawLongBits(newValue));
    }
    
    public double get() {
        return Double.longBitsToDouble(value.get());
    }
    
    public double getAndSet(double newValue) {
        long oldBits = value.getAndSet(Double.doubleToRawLongBits(newValue));
        return Double.longBitsToDouble(oldBits);
    }
    
    public boolean compareAndSet(double expected, double newValue) {
        long expectedBits = Double.doubleToRawLongBits(expected);
        long newBits = Double.doubleToRawLongBits(newValue);
        return value.compareAndSet(expectedBits, newBits);
    }
    
    public boolean weakCompareAndSet(double expected, double newValue) {
        long expectedBits = Double.doubleToRawLongBits(expected);
        long newBits = Double.doubleToRawLongBits(newValue);
        return value.weakCompareAndSet(expectedBits, newBits);
    }
    
    public double addAndGet(double delta) {
        long currentBits, newBits;
        double currentValue, newValue;
        
        do {
            currentBits = value.get();
            currentValue = Double.longBitsToDouble(currentBits);
            newValue = currentValue + delta;
            newBits = Double.doubleToRawLongBits(newValue);
        } while (!value.compareAndSet(currentBits, newBits));
        
        return newValue;
    }
    
    public double getAndAdd(double delta) {
        long currentBits, newBits;
        double currentValue, newValue;
        
        do {
            currentBits = value.get();
            currentValue = Double.longBitsToDouble(currentBits);
            newValue = currentValue + delta;
            newBits = Double.doubleToRawLongBits(newValue);
        } while (!value.compareAndSet(currentBits, newBits));
        
        return currentValue;
    }
    
    @Override
    public String toString() {
        return String.valueOf(get());
    }
}