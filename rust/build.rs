fn main() {
    // FlatBuffers code generation and compatibility fixes were removed as part
    // of the migration to manual ByteBuffer serialization. The manual
    // converters in `src/flatbuffers/conversions.rs` are now canonical.
    //
    // This build script intentionally does nothing to avoid invoking `flatc`
    // or touching generated sources at build time.
}