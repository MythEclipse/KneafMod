#!/bin/bash

# KneafCore Performance Benchmark Script
# This script runs comprehensive benchmarks to measure tick time improvements

echo "=== KneafCore Performance Benchmark ==="
echo "Target: Reduce tick time from 100ms to 20ms or less"
echo ""

# Create benchmark directory
mkdir -p benchmark_results
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BENCHMARK_DIR="benchmark_results/benchmark_$TIMESTAMP"

echo "Creating benchmark directory: $BENCHMARK_DIR"
mkdir -p "$BENCHMARK_DIR"

# Function to run Minecraft server with profiling
run_server_benchmark() {
    local duration=$1
    local test_name=$2
    local extra_args=$3
    
    echo "Running benchmark: $test_name for ${duration}s"
    
    # Start server with profiling enabled
    java -Xmx4G -Xms2G \
        -Dkneaf.benchmark.mode=true \
        -Dkneaf.benchmark.duration=$duration \
        -Dkneaf.benchmark.output="$BENCHMARK_DIR/$test_name" \
        -Dkneaf.performance.enabled=true \
        -Dkneaf.predictive.chunks=true \
        -Dkneaf.villager.optimization=true \
        -Dkneaf.memory.management=true \
        -Dkneaf.simd.operations=true \
        $extra_args \
        -jar build/libs/kneafmod-*.jar nogui > "$BENCHMARK_DIR/${test_name}_server.log" 2>&1 &
    
    local server_pid=$!
    
    # Wait for benchmark duration
    sleep $duration
    
    # Stop server gracefully
    echo "stop" > /proc/$server_pid/fd/0 2>/dev/null || kill -TERM $server_pid
    
    # Wait for server to shut down
    wait $server_pid
    
    echo "Benchmark completed: $test_name"
    echo ""
}

# Function to analyze results
analyze_results() {
    local test_name=$1
    
    echo "Analyzing results for: $test_name"
    
    # Extract performance metrics from log
    if [ -f "$BENCHMARK_DIR/${test_name}_server.log" ]; then
        echo "Performance Summary:"
        grep -E "(Average Tick Time|Maximum Tick Time|Minimum Tick Time|Average TPS|Target Achieved)" \
            "$BENCHMARK_DIR/${test_name}_server.log" | tail -10
        
        # Extract final report
        echo ""
        echo "Final Performance Report:"
        grep -A 20 "=== KneafCore Final Performance Report ===" \
            "$BENCHMARK_DIR/${test_name}_server.log" | tail -15
        
        # Calculate improvement
        local avg_tick_time=$(grep "Average Tick Time:" "$BENCHMARK_DIR/${test_name}_server.log" | tail -1 | grep -o '[0-9.]*' | head -1)
        if [ ! -z "$avg_tick_time" ]; then
            local improvement=$(echo "scale=2; (100 - $avg_tick_time) / 100 * 100" | bc -l 2>/dev/null || echo "N/A")
            echo ""
            echo "Performance Improvement: ${improvement}% reduction from 100ms baseline"
            
            if (( $(echo "$avg_tick_time <= 20" | bc -l 2>/dev/null || echo "0") )); then
                echo "✅ SUCCESS: Target achieved! Tick time is ${avg_tick_time}ms (≤ 20ms)"
            else
                echo "❌ NEEDS IMPROVEMENT: Tick time is ${avg_tick_time}ms (> 20ms target)"
            fi
        fi
    else
        echo "❌ Server log not found for $test_name"
    fi
    
    echo ""
    echo "=================================="
    echo ""
}

# Run comprehensive benchmarks
echo "Starting comprehensive performance benchmarks..."
echo ""

# Test 1: Baseline without optimizations
echo "Test 1: Baseline Performance (30s)"
run_server_benchmark 30 "baseline" "-Dkneaf.performance.enabled=false"

# Test 2: With all optimizations
echo "Test 2: Full Optimizations (60s)"
run_server_benchmark 60 "full_optimizations" ""

# Test 3: Predictive chunk loading only
echo "Test 3: Predictive Chunk Loading (45s)"
run_server_benchmark 45 "predictive_chunks" "-Dkneaf.villager.optimization=false -Dkneaf.memory.management=false -Dkneaf.simd.operations=false"

# Test 4: Villager optimization only
echo "Test 4: Villager Optimization (45s)"
run_server_benchmark 45 "villager_optimization" "-Dkneaf.predictive.chunks=false -Dkneaf.memory.management=false -Dkneaf.simd.operations=false"

# Test 5: Memory management only
echo "Test 5: Memory Management (45s)"
run_server_benchmark 45 "memory_management" "-Dkneaf.predictive.chunks=false -Dkneaf.villager.optimization=false -Dkneaf.simd.operations=false"

# Test 6: SIMD operations only
echo "Test 6: SIMD Operations (45s)"
run_server_benchmark 45 "simd_operations" "-Dkneaf.predictive.chunks=false -Dkneaf.villager.optimization=false -Dkneaf.memory.management=false"

# Analyze all results
echo ""
echo "=== BENCHMARK ANALYSIS ==="
echo ""

analyze_results "baseline"
analyze_results "full_optimizations"
analyze_results "predictive_chunks"
analyze_results "villager_optimization"
analyze_results "memory_management"
analyze_results "simd_operations"

# Generate summary report
echo "=== BENCHMARK SUMMARY REPORT ===" > "$BENCHMARK_DIR/summary_report.txt"
echo "Timestamp: $TIMESTAMP" >> "$BENCHMARK_DIR/summary_report.txt"
echo "Target: Reduce tick time from 100ms to 20ms or less" >> "$BENCHMARK_DIR/summary_report.txt"
echo "" >> "$BENCHMARK_DIR/summary_report.txt"

for test in baseline full_optimizations predictive_chunks villager_optimization memory_management simd_operations; do
    echo "=== $test ===" >> "$BENCHMARK_DIR/summary_report.txt"
    if [ -f "$BENCHMARK_DIR/${test}_server.log" ]; then
        grep -E "(Average Tick Time|Maximum Tick Time|Minimum Tick Time|Average TPS|Target Achieved)" \
            "$BENCHMARK_DIR/${test}_server.log" | tail -5 >> "$BENCHMARK_DIR/summary_report.txt"
    fi
    echo "" >> "$BENCHMARK_DIR/summary_report.txt"
done

echo ""
echo "Benchmark completed! Results saved to: $BENCHMARK_DIR"
echo "Summary report: $BENCHMARK_DIR/summary_report.txt"
echo ""
echo "To view detailed results:"
echo "  ls -la $BENCHMARK_DIR"
echo "  cat $BENCHMARK_DIR/summary_report.txt"
echo ""

# Display quick summary
if [ -f "$BENCHMARK_DIR/summary_report.txt" ]; then
    echo "Quick Summary:"
    grep -E "(Average Tick Time|Target Achieved)" "$BENCHMARK_DIR/summary_report.txt" | head -10
fi

echo ""
echo "=== BENCHMARK COMPLETE ==="