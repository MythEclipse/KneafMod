# Rust Performance Library Build Integration

This directory contains the Rust implementation for high-performance native extensions used by the KneafCore mod. The library is compiled to a Windows DLL (`rustperf.dll`) that is automatically integrated with the Java Gradle build process.

## Prerequisites

1. **Rust Toolchain**: Install via [rustup](https://rustup.rs/)
2. **Windows Target**: For cross-compilation from any OS:

   ```bash
   rustup target add x86_64-pc-windows-gnu
   ```

3. **LLVM Tools**: Required for LTO optimizations

   ```bash
   rustup component add llvm-tools-preview
   ```

## Build Configuration

### Cargo.toml Features

- `jni`: JNI bindings for Java integration
- `lz4`: Compression support
- `serde`: Data serialization
- `windows-sys`: Windows API bindings
- `chrono`: Timestamp handling

### Gradle Integration

The build system automatically:

1. Compiles Rust library when Java project builds
2. Copies DLL to `src/main/resources/natives/`
3. Supports both debug and release modes
4. Validates build prerequisites

## Build Modes

### Release Build (Default)

```bash
./gradlew buildRustNative
```

- Full LTO optimizations
- Stripped binary
- Production-ready performance

### Debug Build

```bash
./gradlew buildRustDebug -PrustDebug=true
```

- No optimizations (`opt-level = 0`)
- Debug symbols included
- Faster compilation

## Manual Build Commands

### From Rust Directory

```bash
# Release build
cargo build --release --target x86_64-pc-windows-gnu

# Debug build  
cargo build --target x86_64-pc-windows-gnu
```

### From Java Project Root

```bash
# Build and copy all Rust artifacts
./gradlew buildRustAll

# Clean and rebuild native libraries
./gradlew cleanSrcNatives copyRustToSrc
```

## Troubleshooting

### Common Issues

1. **"Cargo.toml not found"**
   - Ensure you're running commands from the project root
   - Verify `rust/Cargo.toml` exists

2. **Cross-compilation failures**
   - Install Windows GNU target: `rustup target add x86_64-pc-windows-gnu`
   - Check PATH includes MinGW binaries

3. **JNI Version Mismatch**
   - The library uses JNI v11 (Java 21 compatible)
   - Ensure Java toolchain matches project configuration

## Performance Configuration

See `config/rustperf.toml` for algorithm-specific tuning parameters:

- Sorting algorithms (quicksort/mergesort)
- Compression levels
- Thread pool sizes
- Memory allocation strategies

## Dependencies

### Cargo Dependencies

- `jni = "0.21.1"` - JNI bindings
- `lz4 = "1.34.0"` - Compression
- `serde = { version = "1.0", features = ["derive"] }` - Serialization
- `windows-sys = { version = "0.59", features = ["Win32_Foundation", "Win32_System_Threading"] }` - Windows API
- `chrono = { version = "0.4", features = ["serde"] }` - Timestamps

### Gradle Dependencies

- `io.github.astonbitecode:j4rs:0.17.0` - Rust-Java bridge
- `org.lz4:lz4-java:1.8.0` - Java LZ4 bindings (for consistency)

## Version Compatibility

| Rust Version | JNI Version | Java Version |
|--------------|-------------|--------------|
| 1.70+        | 11          | 21           |
| 1.65-1.69    | 11          | 21           |
| <1.65        | ❌ Unsupported | ❌ Unsupported |

## Security Considerations

- The native library runs with the same privileges as the Java process
- All JNI calls are validated for null pointers
- Memory is properly released after JNI operations
- No unsafe code is exposed to Java runtime

## CI/CD Integration

The build system includes:

- Automatic dependency validation
- Build failure handling with clear error messages
- Incremental builds (only rebuilds when sources change)
- Cross-platform compatibility checks

For CI environments, ensure:

- Rust toolchain is installed
- Windows target is added
- Appropriate PATH variables are set for MinGW tools
