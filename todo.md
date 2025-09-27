 Todo List – Mod Performa Rust + NeoForge
1. 🎯 Desain Arsitektur

 Tentukan scope: mod hanya server-side (bisa join client vanilla/modpack).

 Pilih Rust FFI layer untuk integrasi dengan NeoForge:

jni (panggil Rust dari Java) atau

j4rs / jni-rs wrapper.

 Struktur project:

java/ → NeoForge entry point (mod loader).

rust/ → library optimasi (entity tick scheduler, item dedup).

2. ⚡ Optimasi Tick (Selective)

 Hook ke ServerTickEvent di NeoForge.

 Ambil daftar entity tiap tick.

 Implementasikan aturan dynamic throttling:

 Jika entity dekat player (<32 blok) → tick normal.

 Jika entity agak jauh (32–128 blok) → tick 1/2.

 Jika entity jauh (>128 blok) → tick 1/5.

 BlockEntity (mesin mod, furnaces, mekanism, dll) → selalu normal.

 Buat konfigurasi (config/rustperf.toml) biar server admin bisa atur radius & rate.

3. 📦 Optimasi Item Entity

 Tambahkan sistem item stack merging di Rust:

 Jika banyak item entity identik di chunk → merge jadi 1 stack.

 Batasi jumlah item entity per chunk (misalnya 100).

 Konfigurasi opsional: auto-despawn item setelah X detik.

 Logging: berapa item yang dihapus/digabung setiap menit.

4. 🧠 AI & Mob Optimization

 Pindahkan AI pathfinding mob jauh ke mode simplified:

 Gunakan tick rate lebih lambat.

 Skip path recalculation kalau tidak ada player target.

 Entity pasif (sapi, ayam, babi) jauh dari player → AI mati sementara.

 Entity hostile → tetap aktif kalau ada player nearby.

5. 🧩 Integrasi dengan Mod Lain

 Tambahkan compat layer: cek apakah mod tertentu terpasang (ModList.get().isLoaded()).

 Jika mod performa lain ada (Lithium, Starlight, FerriteCore):

 Tambah warning di log.

 Bisa auto-disable fitur yang bertabrakan.

 Tambahkan opsi di mods.toml:

 type="incompatible" untuk hard block (jika ingin).

6. 📊 Monitoring & Debugging

 Buat command /rustperf status untuk lihat:

TPS saat ini.

Jumlah entity ticked normal vs throttled.

Jumlah item entities merged/removed.

 Tambahkan debug log untuk analisa performa:

Save ke logs/rustperf.log.

 Integrasi metrics ke Prometheus/Grafana (opsional).

7. 🔒 Stabilitas & Safety

 Validasi semua akses entity/thread agar tidak race condition.

 Gunakan Arc<Mutex<>> atau RwLock di Rust untuk data shared.

 Fallback ke tick normal jika Rust FFI error.

 Jangan optimasi critical logic (redstone dekat player, machine block, boss entity).

8. 📦 Build & Distribusi

 Buat Gradle build script untuk compile Rust → .dll/.so/.dylib.

 Package jadi .jar dengan Rust lib di dalamnya.

 Test di server dengan 50+ mod (modpack besar).

 Upload ke Modrinth/CurseForge dengan tag:

Performance

Server-Side

Rust Integration

9. 🧪 Benchmark & Simulasi

 Buat world stress-test (2000 mob, 500 item, 100 mesin).

 Catat hasil TPS vanilla vs dengan RustPerf.

 Uji kombinasi dengan mod lain (Create, Mekanism, AE2).

 Dokumentasikan hasil (grafik TPS, CPU, RAM).

⚡ Dengan todo ini, modmu akan:

Meringankan server (CPU/RAM lebih hemat).

Tidak memperlambat mesin mod (karena selective).

Fleksibel & aman dipakai di modpack besar.