Task/Result Envelope Specification

Overview
========
This project uses a compact, fixed-layout binary envelope for sending tasks from Java to the native worker and for returning results back to Java.  The format is intentionally small and simple to keep JNI overhead low.

TaskEnvelope (Java -> Native)
-----------------------------
Layout (all multi-byte values are little-endian):

- bytes [0..8)   : u64 taskId (little-endian)
- byte  [8]      : u8  taskType
- bytes [9..13)  : u32 payload_len (little-endian)
- bytes [13..13+payload_len) : payload bytes (opaque)

Notes:
- `taskType` is an application-defined opcode that chooses the worker behavior.
- `payload` is opaque to the worker unless the chosen taskType defines a schema. It may be text (JSON/UTF-8) or custom binary depending on the task.

ResultEnvelope (Native -> Java)
-------------------------------
Layout (all multi-byte values are little-endian):

- bytes [0..8)   : u64 taskId
- byte  [8]      : u8  status (0 = ok, 1 = error)
- bytes [9..13)  : u32 payload_len
- bytes [13..13+payload_len) : payload bytes

Notes:
- `taskId` should be the same value from the TaskEnvelope when possible. If the native side cannot parse the incoming task id, the returned taskId may be 0.
- For `status == 0`, `payload` contains the task result. For domain-specific tasks it may be JSON. For `status == 1`, `payload` contains a UTF-8 error message describing the failure.

Defined taskType values (current mapping)
-----------------------------------------
- 0x01 (1): ECHO
  - Worker simply echoes payload back into the result payload. Used by roundtrip tests.
- 0x02 (2): HEAVY_CPU_JOB
  - Payload: ASCII/UTF-8 decimal string containing `n` (example: "10000"). Worker computes sum of squares 0..n-1 in parallel and returns JSON: {"task":"heavy","n":n, "sum":"<decimal>"}.
- 0x03 (3): ITEM_OPTIMIZE (reserved)
  - Payload: domain-specific. Suggested representation: compact JSON or small binary record describing item positions/ids. Not yet standardized; use JSON for prototyping.
- 0xFF (255): PANIC_TEST
  - Special test opcode that instructs the worker to deliberately panic (for verifying panic-safety / envelope-on-error behavior).

Versioning and compatibility
----------------------------
- This envelope has no explicit version byte yet. If you need to evolve the format in the future, prepend a single version byte at offset 0 and shift fields accordingly.
- When changing the layout, ensure both Java and native sides are updated together and add a new integration test.

Error handling
--------------
- Native code should always return a valid ResultEnvelope (status 0 or 1). Panics on the native side must be caught and translated into an error ResultEnvelope to avoid crashing the JVM.

Best practices
--------------
- When payloads are purely numeric arrays (large matrices), prefer using direct ByteBuffers with the `NativeFloatBuffer` helper to avoid copies.
- For heavy data shapes consider using a compact binary serialization (bincode, flatbuffers, capnp) for performance.

Contact
-------
- Keep this file updated when adding new task types or changing the envelope layout.
