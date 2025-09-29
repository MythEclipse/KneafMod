# ✅ To-Do List Berbasis Fitur (Versi Tanpa Freeze Entity)

1. Core Chunk Storage Layer

 Fitur: Abstract ChunkSerializer untuk konversi LevelChunk ⇆ binary (NBT / MessagePack)

 Fitur: DatabaseAdapter (Sled / RocksDB / LMDB) → put_chunk(key, data) / get_chunk(key)

 Fitur: Checksum / Versioning untuk menjaga integritas data

1. RAM Cache Management

 Fitur: ChunkCache Map (key → in-memory chunk) dengan kapasitas maksimum

 Fitur: Eviction Policy Engine (LRU / Distance / Hybrid)

 Fitur: Chunk State Tracker (Hot / Cold / Dirty / Serialized)

1. Intercept Chunk Load & Unload (Hook ke NeoForge)

 Fitur: Override unload event → simpan ke DB, jangan langsung buang

 Fitur: Override load event → cek cache → DB → fallback ke generator

 Fitur: Async Loader Thread Pool untuk hindari TPS drop

1. Preloading & Prediction

 Fitur: Predictive Chunk Loader saat pemain bergerak

 Fitur: Teleport Burst Handler untuk load paralel dalam jumlah besar

1. Monitoring & Debugging

 Fitur: /chunkcache stats → tampilkan hit/miss, eviction count, DB latency

 Fitur: In-game profiler hook → grafik TPS vs DB access

 Fitur: Config (YAML/TOML) → atur cache size, eviction mode, DB location

1. Fail-Safe & Data Recovery

 Fitur: Auto Backup Snapshot database berkala

 Fitur: Fallback to Vanilla jika database error/corrupt
