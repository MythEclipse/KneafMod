// We no longer use FlatBuffers-generated sources for JNI binary paths.
// The project uses manual little-endian byte layouts implemented in
// `conversions.rs` to serialize/deserialize data between Java and Rust.
//
// Keep only the conversions module here to make the manual format the
// canonical API surface for the native crate. The original generated
// FlatBuffers files are left on disk for reference but are not included
// into the crate root to avoid accidental usage.

pub mod conversions;