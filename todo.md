### ✅ **Todo List – Mod Performa Rust + NeoForge**

---

#### **1. 🎯 Desain Arsitektur**

- [ ] Tentukan scope mod (hanya server-side agar klien vanilla bisa bergabung).
- [ ] Pilih FFI layer untuk integrasi Rust dengan NeoForge (misalnya, `jni`, `j4rs`, atau `jni-rs`).
- [ ] Buat struktur proyek yang jelas:
  - [ ] Folder `rust/` untuk library optimasi inti.

---

#### **2. ⚡ Optimasi Tick (Selective)**

- [ ] Implementasikan hook ke `ServerTickEvent` di NeoForge.
- [ ] Buat fungsi untuk mengambil daftar entitas di setiap tick server.
- [ ] **Kembangkan aturan *dynamic throttling* khusus untuk *item drop* (item entity):**
  - [ ] **Dekat (< 32 blok):** Tick normal.
  - [ ] **Menengah (32–128 blok):** Tick rate 1/2 dari normal.
  - [ ] **Jauh (> 128 blok):** Tick rate 1/5 dari normal.
- [ ] Pastikan `BlockEntity` (seperti mesin dari mod, furnace) dikecualikan dan selalu berjalan pada tick normal.
- [ ] Buat file konfigurasi (`config/rustperf.toml`) agar admin server dapat menyesuaikan radius dan tick rate.

---

#### **3. 📦 Optimasi Item Entity**

- [ ] Buat sistem *item stack merging* di dalam library Rust.
  - [ ] Jika ada beberapa item sejenis di satu chunk, gabungkan menjadi satu entitas tumpukan.
- [ ] Implementasikan batas jumlah item entity per chunk (misalnya, maksimal 100).
- [ ] Tambahkan opsi konfigurasi untuk *auto-despawn* item setelah durasi tertentu.
- [ ] Atur sistem logging untuk mencatat jumlah item yang berhasil digabung atau dihapus setiap menit.

---

#### **4. 🧠 AI & Mob Optimization**

- [ ] Alihkan AI *pathfinding* untuk mob yang jauh dari pemain ke mode yang lebih sederhana (*simplified*).
  - [ ] Gunakan tick rate AI yang lebih lambat.
  - [ ] Lewati kalkulasi ulang jalur jika tidak ada target pemain.
- [ ] Nonaktifkan AI untuk entitas pasif (sapi, ayam, babi) yang berada jauh dari pemain.
- [ ] Pastikan entitas musuh (*hostile*) tetap aktif jika ada pemain di dekatnya.

---

#### **5. 🧩 Integrasi dengan Mod Lain**

- [ ] Kembangkan *compatibility layer* untuk mendeteksi mod lain yang terpasang (`ModList.get().isLoaded()`).
- [ ] Tangani konflik dengan mod performa lain (seperti Lithium, Starlight, FerriteCore):
  - [ ] Tampilkan peringatan di log jika terdeteksi.
  - [ ] Tambahkan opsi untuk menonaktifkan fitur yang berpotensi bentrok secara otomatis.
- [ ] Definisikan mod yang tidak kompatibel di `mods.toml` untuk mencegah pemuatan bersama jika diperlukan.

---

#### **6. 📊 Monitoring & Debugging**

- [ ] Buat command kustom (misal, `/rustperf status`) untuk menampilkan metrik real-time:
  - [ ] TPS server saat ini.
  - [ ] Jumlah entitas yang di-tick normal vs. yang di-*throttle*.
  - [ ] Total item yang digabung atau dihapus.
- [ ] Implementasikan debug log untuk analisis performa yang disimpan di `logs/rustperf.log`.
- [ ] (Opsional) Integrasikan metrik dengan alat eksternal seperti Prometheus atau Grafana.

---

#### **7. 🔒 Stabilitas & Safety**

- [ ] Pastikan semua akses ke entitas dan data bersama bersifat *thread-safe* untuk menghindari *race condition*.
- [ ] Gunakan `Arc<Mutex<>>` atau `RwLock` di Rust untuk mengelola data bersama.
- [ ] Buat mekanisme *fallback* ke tick normal jika panggilan FFI ke Rust gagal.
- [ ] Buat daftar pengecualian untuk tidak mengoptimalkan logika kritis (misalnya, redstone, mesin, dan entitas bos).

---

#### **8. 📦 Build & Distribusi**

- [ ] Konfigurasikan *build script* Gradle untuk mengkompilasi kode Rust menjadi library dinamis (`.dll`, `.so`, `.dylib`).
- [ ] Atur proses build untuk mengemas library Rust ke dalam file `.jar` mod.
- [ ] Lakukan pengujian menyeluruh di server yang menjalankan modpack besar (50+ mod).
- [ ] Siapkan halaman rilis di Modrinth/CurseForge dengan tag yang relevan: `Performance`, `Server-Side`, `Rust`.

---

#### **9. 🧪 Benchmark & Simulasi**

- [ ] Buat dunia *stress-test* dengan beban tinggi (misal, 2000 mob, 500 item, 100 mesin).
- [ ] Lakukan benchmark dan catat perbandingan TPS, penggunaan CPU, dan RAM antara server vanilla dengan server yang menggunakan mod ini.
- [ ] Uji performa dan stabilitas saat dijalankan bersama mod-mod berat lainnya (seperti Create, Mekanism, AE2).
- [ ] Dokumentasikan hasil benchmark dalam bentuk grafik dan tabel untuk presentasi.
