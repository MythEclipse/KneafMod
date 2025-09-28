
# TODO: Pindahkan pekerjaan berat server (tick) ke native Rust via JNI/FFI — Rencana lengkap

Dokumen ini adalah panduan langkah demi langkah untuk memigrasikan pekerjaan CPU/IO intensif (mis. perhitungan fisika, pathfinding, simulasi, hashing, pemrosesan blok massif) dari thread tick Minecraft ke kode native Rust yang dijalankan di thread/worker terpisah, berkomunikasi lewat JNI/FFI.

Tujuan akhir
- Mengurangi latency blocking pada tick thread dengan memindahkan kerja berat ke native Rust.
- Menyediakan API yang aman, terdokumentasi, dan mudah dites antara Java (Neoforge) dan Rust.

Asumsi lingkungan
- Proyek berbentuk mod Neoforge/Forge pada Java menggunakan Gradle (lihat root project). 
- Crate Rust berada di `rust/` dan Gradle sudah atau mudah diatur untuk membangun crate (`buildRust*` tasks terlihat ada).
- Sudah ada beberapa helper JNI/FFI minimal: `NativeBridge` dan `NativeFloatBuffer` di `src/main/java/com/kneaf/core/performance`.

Kontrak data & API (detail teknis)

1) Bentuk pesan (task envelope)
- Pilihan rekomendasi: bincode (serde) untuk efisiensi, dengan fallback JSON untuk debug.
- Struktur Rust (serde):

```rust
#[derive(Serialize, Deserialize)]
pub struct TaskEnvelope {
    pub task_id: u64,
    pub task_type: u8,
    pub payload: Vec<u8>, // bincode atau json bytes
}

#[derive(Serialize, Deserialize)]
pub struct ResultEnvelope {
    pub task_id: u64,
    pub status: u8, // 0 = ok, 1 = error
    pub payload: Vec<u8>, // result bytes or error message bytes
}
```

- Java counterpart: plain POJO/utility that serializes/deserializes to/from byte[] (gunakan library kecil seperti `kryo` atau manual bincode-equivalent; lebih sederhana: Java buat struct -> JSON bytes for now for fastest dev).

2) JNI surface (minimal, sudah ada fungsi MVP)
- Fungsi JNI yang direkomendasikan (Java signatures):
  - `native long nativeCreateWorker(int concurrency)`
  - `native void nativePushTask(long handle, byte[] payload)`
  - `native byte[] nativePollResult(long handle)` // returns null when none
  - `native void nativeDestroyWorker(long handle)`

3) Ownership & memory rules
- Untuk payload kecil: gunakan `byte[]` (copy, safe).
- Untuk buffer besar (arrays matriks, voxel dumps): gunakan direct `ByteBuffer` yang dialokasikan di Rust atau Java, lalu transfer pointer lewat `NewDirectByteBuffer` + Cleaner di Java untuk free.
- Jika Rust mengembalikan direct ByteBuffer, Java wajib merapikannya (Cleaner/AutoCloseable) dan panggil `freeFloatBufferNative` saat GC/close.

Langkah implementasi terperinci (dengan file & verifikasi)

PHASE 0 — DESIGN & MVP

Step 0.1 — Desain envelope & roundtrip test (File: `rust/src/lib.rs`, `src/main/java/com/kneaf/core/performance/TaskEnvelope.java`)
- Tugas:
  - Implement `TaskEnvelope` & `ResultEnvelope` di Rust (serde + bincode). 
  - Buat helper Java `TaskEnvelope` yang dapat serialisasikan ke byte[] (JSON initially for speed of implementation). 
  - Tambah test: Java -> native JNI helper yang hanya decode/encode dan return same bytes for roundtrip.
- Verifikasi:
  - `cd rust; cargo test` untuk unit Rust.
  - `.\gradlew test --console=plain` untuk Java roundtrip test.

Step 0.2 — MVP worker (File: `rust/src/lib.rs`, `src/main/java/com/kneaf/core/performance/NativeBridge.java`, tests in `src/test/java/...`)
- Tugas:
  - Pastikan Rust memiliki worker struct yang spawn thread(s), menerima Vec<u8> tasks via `crossbeam-channel`, memproses, dan mengirim hasil ke results channel.
  - Pastikan JNI implementations benar (gunakan jni-rs idiom: `convert_byte_array`, `byte_array_from_slice`, `new_direct_byte_buffer`).
  - Expose `nativeCreateWorker`, `nativePushTask`, `nativePollResult`, `nativeDestroyWorker`.
  - Java tests: enqueue test payload, poll until result, assert equality.
- Verifikasi:
  - `.\gradlew test --console=plain` (already wired in repo; buildRust is integrated).
  - Add JUnit test `NativeBridgeTest` to assert roundtrip.

PHASE 1 — INTEGRASI SERVER & KEAMANAN

Step 1.1 — Non-blocking enqueue dari tick (File: server tick handler, e.g. in `src/main/java/.../TickHandler.java`)
- Tugas:
  - Di lokasi yang menjalankan tick event (server side), ubah logika: jika ada pekerjaan berat, kumpulkan data minimal (chunk coords, region id, sample) dan buat `TaskEnvelope`.
  - Panggil `NativeBridge.nativePushTask(workerHandle, envelopeBytes)` — jangan block jika queue penuh; gunakan backpressure policy:
    - prefer: drop task and log if non-critical
    - fallback: schedule synchronous execution on dedicated `ExecutorService` (configurable)
- Verifikasi:
  - Jalankan server dev, aktifkan verbose logging, pastikan tick thread tidak stuck > 50 ms ketika tugas di-submit.

Step 1.2 — Polling dan apply results (File: same tick handler or dedicated scheduler)
- Tugas:
  - Buat scheduled poll (e.g., run at end of tick or as scheduled task on server main thread) yang memanggil `nativePollResult` berulang sampai null.
  - Deserialisasi `ResultEnvelope` dan apply perubahan ke world using server thread only.
- Verifikasi:
  - Pastikan perubahan dunia terjadi only on server thread (no concurrent world mutation).
  - Pastikan polling cepat (kosong check cheap), dan tidak menyebabkan long GC pauses.

Step 1.3 — Error handling & fallbacks
- Tugas:
  - Di Rust, wrap task processing with `std::panic::catch_unwind` dan return structured error payload if panic.
  - Di Java, jika `status != 0` => log & optionally perform fallback synchronous method.
- Verifikasi:
  - Inject malformed payload or explicitly cause panic in test worker; assert Java receives error envelope and server continues.

PHASE 2 — RESILIENCE & PERFORMANCE

Step 2.1 — Thread pool & configuration (File: `rust/src/lib.rs`, `config/kneaf-performance.properties`)
- Tugas:
  - Replace single-thread worker with configurable thread pool using `crossbeam` + worker threads or `rayon` scoped pool.
  - Expose `nativeCreateWorker(int concurrency)` to set pool size.
  - Add configuration to `kneaf-performance.properties` (example `native.worker.threads=4`).
- Verifikasi:
  - Stress test with many concurrent tasks; measure throughput and queue latency.

Step 2.2 — Large buffers & zero-copy (File: `rust/src/lib.rs`, `src/main/java/.../NativeFloatBufferAllocation.java`)
- Tugas:
  - For large data (e.g., chunk slices), return direct ByteBuffers from Rust or accept direct ByteBuffer from Java.
  - Implement `generateFloatBufferNative` and `freeFloatBufferNative` in Rust and Java wrapper with `Cleaner`.
- Verifikasi:
  - Long-run test for memory leaks. Use `jcmd`/`jmap` on JVM and check native memory growth.

PHASE 3 — PACKAGING & CI

Step 3.1 — Build tasks & CI
- Tugas:
  - Ensure `build.gradle` builds Rust and copies native lib to runtime path (e.g., `run/` or mod artifact).
  - Add GitHub Actions (or similar) matrix to build native libs for Windows/Linux/Mac and attach artifacts.
- Verifikasi:
  - CI artifacts present for all platforms; sample server startup with artifact works.

Step 3.2 — Tests & Benchmarks
- Tugas:
  - Add Rust unit tests for processing logic.
  - Add Java JUnit integration tests that spin a minimal headless server (if possible) and run tasks.
  - Add small benchmark harness to measure average task latency and throughput under synthetic load.
- Verifikasi:
  - `cargo test` green, `.\gradlew test` green, benchmarks produce reasonable numbers.

Edge cases, risks, mitigations
- Double-free or leak native memory — mitigate with clear ownership rules and Java `Cleaner` + `free` native function.
- Native panic -> JVM crash — mitigate by `catch_unwind` and returning an error envelope.
- ClassLoader / hot-reload issues — avoid unloading native library; if needed, coordinate with mod loader to restart JVM for reload.
- ABI/Platform differences — build and test on each target; use `cdylib` crate type and platform-specific naming.

Definition of Done (DoD) per phase
- Phase 0 Done: Roundtrip tests pass (Java↔Rust), JNI APIs compile, minimal JUnit tests pass.
- Phase 1 Done: Server tick submits tasks non-blocking; polling applies results without concurrent world modification; smoke server run (30–60s) shows reduced tick blocking.
- Phase 2 Done: Configurable worker pool, memory-safe zero-copy for large buffers, no memory leaks after long-run test.
- Phase 3 Done: CI builds and publishes native libs; packaging places correct native lib in runtime.

Checklist cepat (pre-merge)
- [ ] Rust: `cargo fmt -- --check`, `cargo clippy` (optional), `cargo test`.
- [ ] Java: `.\gradlew test --console=plain`, address any warnings from static analysis.
- [ ] Smoke: headless server run 60s with synthetic load (<some command>), verify tick latency distribution.
- [ ] CI: GitHub Actions passing with native artifacts.

Command snippet (PowerShell) — build & test locally

```powershell
cd .\rust
cargo build --release
cargo test
cd ..
.\gradlew buildRust test --console=plain
```

Files likely to change (summary)
- `rust/src/lib.rs` — worker, JNI bindings, task processing, pooling, buffer alloc/free.
- `Cargo.toml` — add deps (crossbeam-channel, serde, bincode, jni, blake3 if needed).
- `src/main/java/com/kneaf/core/performance/NativeBridge.java` — native declarations and helpers.
- `src/main/java/com/kneaf/core/performance/TaskEnvelope.java` — Java serializer/deserializer.
- Server mod files (tick handler) — integrate enqueue & polling. Location varies: search for tick listener classes.
- `build.gradle` — ensure Rust build tasks and copy native lib.

Testing matrix & metrics to collect
- Latency per task (ms) from enqueue->result
- Tick duration distribution (ms) before/after migration
- Throughput (tasks/s)
- Native memory usage & JVM native memory growth over time

Minimal implementation plan timeline (rough)
- Day 0–1: Phase 0 (design + MVP + roundtrip tests)
- Day 1–2: Phase 1 (integrasi tick + polling + error handling)
- Day 3: Phase 2 (thread pool, large buffer support)
- Day 4: Packaging & CI, benchmarks, fixes

Next actions saya bisa langsung lakukan (sebutkan pilihan):
- A) Implement envelope (bincode/JSON) + Java serializer + roundtrip tests (priority recommended).
- B) Wire tick handler to enqueue + scheduled poll + JUnit integration test.
- C) Implement configurable thread-pool in Rust and expose via `nativeCreateWorker(concurrency)`.

Jika ingin saya lanjutkan langsung, katakan pilihan (A/B/C). Jika mau, saya juga bisa mulai dengan merapikan `todo.md` atau membuat branch kerja di repo dan langsung mengerjakan Phase 0.

