@echo off
REM KneafCore Performance Benchmark Script for Windows
REM This script runs comprehensive benchmarks to measure tick time improvements

echo === KneafCore Performance Benchmark ===
echo Target: Reduce tick time from 100ms to 20ms or less
echo.

REM Create benchmark directory
set TIMESTAMP=%date:~-4,4%%date:~-10,2%%date:~-7,2%_%time:~0,2%%time:~3,2%%time:~6,2%
set TIMESTAMP=%TIMESTAMP: =0%
set BENCHMARK_DIR=benchmark_results\benchmark_%TIMESTAMP%

echo Creating benchmark directory: %BENCHMARK_DIR%
mkdir "%BENCHMARK_DIR%" 2>nul

REM Function to run Minecraft server with profiling
:run_server_benchmark
set duration=%1
set test_name=%2
set extra_args=%3

echo Running benchmark: %test_name% for %duration%s

REM Start server with profiling enabled
start /B cmd /C "java -Xmx4G -Xms2G -Dkneaf.benchmark.mode=true -Dkneaf.benchmark.duration=%duration% -Dkneaf.benchmark.output=%BENCHMARK_DIR%\%test_name% -Dkneaf.performance.enabled=true -Dkneaf.predictive.chunks=true -Dkneaf.villager.optimization=true -Dkneaf.memory.management=true -Dkneaf.simd.operations=true %extra_args% -jar build\libs\kneafmod-*.jar nogui > %BENCHMARK_DIR%\%test_name%_server.log 2>&1"

REM Wait for benchmark duration
timeout /T %duration% /NOBREAK >nul

REM Stop server gracefully (this is a simplified approach)
taskkill /F /IM java.exe >nul 2>&1

echo Benchmark completed: %test_name%
echo.
goto :eof

REM Run comprehensive benchmarks
echo Starting comprehensive performance benchmarks...
echo.

REM Test 1: Baseline without optimizations
echo Test 1: Baseline Performance (30s)
call :run_server_benchmark 30 "baseline" "-Dkneaf.performance.enabled=false"

REM Test 2: With all optimizations
echo Test 2: Full Optimizations (60s)
call :run_server_benchmark 60 "full_optimizations" ""

REM Test 3: Predictive chunk loading only
echo Test 3: Predictive Chunk Loading (45s)
call :run_server_benchmark 45 "predictive_chunks" "-Dkneaf.villager.optimization=false -Dkneaf.memory.management=false -Dkneaf.simd.operations=false"

REM Test 4: Villager optimization only
echo Test 4: Villager Optimization (45s)
call :run_server_benchmark 45 "villager_optimization" "-Dkneaf.predictive.chunks=false -Dkneaf.memory.management=false -Dkneaf.simd.operations=false"

REM Test 5: Memory management only
echo Test 5: Memory Management (45s)
call :run_server_benchmark 45 "memory_management" "-Dkneaf.predictive.chunks=false -Dkneaf.villager.optimization=false -Dkneaf.simd.operations=false"

REM Test 6: SIMD operations only
echo Test 6: SIMD Operations (45s)
call :run_server_benchmark 45 "simd_operations" "-Dkneaf.predictive.chunks=false -Dkneaf.villager.optimization=false -Dkneaf.memory.management=false"

REM Analyze results
echo.
echo === BENCHMARK ANALYSIS ===
echo.

call :analyze_results "baseline"
call :analyze_results "full_optimizations"
call :analyze_results "predictive_chunks"
call :analyze_results "villager_optimization"
call :analyze_results "memory_management"
call :analyze_results "simd_operations"

REM Generate summary report
echo === BENCHMARK SUMMARY REPORT === > "%BENCHMARK_DIR%\summary_report.txt"
echo Timestamp: %TIMESTAMP% >> "%BENCHMARK_DIR%\summary_report.txt"
echo Target: Reduce tick time from 100ms to 20ms or less >> "%BENCHMARK_DIR%\summary_report.txt"
echo. >> "%BENCHMARK_DIR%\summary_report.txt"

for %%t in (baseline full_optimizations predictive_chunks villager_optimization memory_management simd_operations) do (
    echo === %%t === >> "%BENCHMARK_DIR%\summary_report.txt"
    if exist "%BENCHMARK_DIR%\%%t_server.log" (
        findstr /C:"Average Tick Time:" /C:"Maximum Tick Time:" /C:"Minimum Tick Time:" /C:"Average TPS:" /C:"Target Achieved:" "%BENCHMARK_DIR%\%%t_server.log" >> "%BENCHMARK_DIR%\summary_report.txt" 2>nul
    )
    echo. >> "%BENCHMARK_DIR%\summary_report.txt"
)

echo.
echo Benchmark completed! Results saved to: %BENCHMARK_DIR%
echo Summary report: %BENCHMARK_DIR%\summary_report.txt
echo.
echo To view detailed results:
echo   dir "%BENCHMARK_DIR%"
echo   type "%BENCHMARK_DIR%\summary_report.txt"
echo.

REM Display quick summary
if exist "%BENCHMARK_DIR%\summary_report.txt" (
    echo Quick Summary:
    findstr /C:"Average Tick Time:" /C:"Target Achieved:" "%BENCHMARK_DIR%\summary_report.txt" | head -10 2>nul || findstr /C:"Average Tick Time:" /C:"Target Achieved:" "%BENCHMARK_DIR%\summary_report.txt"
)

echo.
echo === BENCHMARK COMPLETE ===

goto :eof

:analyze_results
set test_name=%1
echo Analyzing results for: %test_name%

if exist "%BENCHMARK_DIR%\%test_name%_server.log" (
    echo Performance Summary:
    findstr /C:"Average Tick Time:" /C:"Maximum Tick Time:" /C:"Minimum Tick Time:" /C:"Average TPS:" /C:"Target Achieved:" "%BENCHMARK_DIR%\%test_name%_server.log" | tail -10 2>nul
    
    echo.
    echo Final Performance Report:
    findstr /A 20 "=== KneafCore Final Performance Report ===" "%BENCHMARK_DIR%\%test_name%_server.log" >nul && (
        findstr /A 20 "=== KneafCore Final Performance Report ===" "%BENCHMARK_DIR%\%test_name%_server.log" | tail -15 2>nul
    ) || (
        echo Report section not found in log
    )
    
    echo.
    echo ===================================
    echo.
) else (
    echo Server log not found for %test_name%
)
goto :eof