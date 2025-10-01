
Archived generated FlatBuffers sources

Why these files are archived
---------------------------
These files are the original Rust sources produced by the FlatBuffers compiler (flatc). They have been preserved here in full for reference and for possible restoration.

Why they are not compiled
-------------------------
The project migrated to a manual little-endian ByteBuffer layout implemented in `conversions.rs`. To avoid accidental usage of the generated FlatBuffers sources and to keep the crate surface minimal and explicit, the generated files were removed from the crate root and subdirectories and placed here as an archive. The manual `conversions.rs` is the canonical implementation used at runtime.

How to restore a generated file
------------------------------
Option A — restore from this archive (recommended when you want an exact copy):
1. Copy the desired `_generated_full.rs` file back into `rust/src/flatbuffers/` (or its subdirectory) with the original filename (for example `block_generated.rs`).

PowerShell example:

```powershell
# from repository root
Copy-Item -Path "rust/src/flatbuffers/generated_archive/block_generated_full.rs" -Destination "rust/src/flatbuffers/block_generated.rs"
```

Option B — regenerate using flatc (if you have the .fbs schema files):
1. Install flatc (FlatBuffers compiler) for your platform.
2. Run flatc with the Rust output option against the schema files. Example:

```powershell
flatc --rust schema/block.fbs -o rust/src/flatbuffers
```

(Adjust schema paths and output dir to match your repo layout.)

Verification after restore
--------------------------
- Rebuild the Rust crate and run unit tests:

```powershell
cd rust
cargo test --lib
```

- Re-run the Gradle test task (this will also build the native artifact used by Java tests):

```powershell
cd ..\
.\gradlew.bat test
```

Notes and warnings
------------------
- Do not reintroduce generated FlatBuffers files into the build unless you intend to switch back to FlatBuffers; the manual converters in `conversions.rs` are the source of truth for the binary layout.
- Keep the archive intact to preserve a history and to make it easy to recover generated code later.
