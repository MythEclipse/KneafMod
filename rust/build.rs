fn main() {
    // FlatBuffers code is manually generated in src/flatbuffers/mod.rs
    println!("cargo:rerun-if-changed=src/flatbuffers/mod.rs");
}