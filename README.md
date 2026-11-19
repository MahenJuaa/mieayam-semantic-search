# Pencarian Semantik Mie Ayam (Apache Jena)

Satu kotak pencarian. Ketik "halal", "pedas", "tanpa babi", "bakso tanpa telur", dst.
Kalimat diterjemahkan ke konsep di ontologi, lalu dijalankan sebagai query SPARQL.

## NetBeans
1. File > Open Project... > pilih folder MieAyamWeb (JDK 17+).
2. Klik kanan proyek > Run (F6). Browser terbuka di http://localhost:8080
   (port otomatis naik sampai 8089 jika terpakai; lihat panel Output).

## Terminal
    mvn compile exec:java

## Cara kerja
- `MieAyam_lengkap.rdf` : ontologi OWL (32 menu, 16 kelas Filter*).
- `MieAyamWeb.java` : memuat ontologi dengan Jena dan menyusun kosakata pencarian dari ontologi itu sendiri:
  1. kelas `Filter*` (equivalentClass + hasValue): "halal", "kuah bening", "berbakso", ...
  2. individu komponen yang dipakai menu: "bakso sapi", "sawi hijau", ...
  3. kelas komponen lewat `rdfs:subClassOf`: "daging" otomatis mencakup ayam, sapi, dan babi.
- `SemanticParser.java` : memecah kalimat, mencocokkan frasa terpanjang ke konsep, menangani
  negasi ("tanpa", "bukan", "tidak"), lalu membuat query SPARQL. Kata yang tidak ada di ontologi
  dicari pada nama menu.
- Menambah kata baru cukup dengan mengubah ontologi (mis. kelas Filter baru); tidak perlu mengubah kode.
- Bagian "Lihat query SPARQL" di halaman menampilkan query yang dihasilkan untuk tiap pencarian.

## Catatan
- Kata kunci dalam satu kelompok digabung AND ("halal pedas"). Kata "atau" memisahkan kelompok yang
  digabung OR lewat UNION di SPARQL ("haram atau pedas" = menu haram + menu pedas).
- "tanpa babi atau telur" berarti keduanya ditolak (penolakan berlanjut lewat "dan"/"atau").
- Kalori (`mie:kalori`) dicari lewat angka: "rendah kalori", "diet", "tinggi kalori", "di bawah 500 kalori",
  "minimal 600 kkal", "400-500 kalori", "520 kalori". Ontologi tidak punya kelas Filter untuk kalori, jadi batas
  "rendah" (<= 500) dan "tinggi" (>= 700) ada di `SemanticParser.LOW_MAX` / `HIGH_MIN`. Angka hanya dibaca
  sebagai kalori bila ada kata "kalori"/"kkal" di dekatnya.
- Sinonim kecil ("haram", "vegan", "sayuran", "pedes") ada di `SemanticParser.SYN`.

## Tampilan (desain split hero)
- `src/main/resources/index.html` : satu file HTML/CSS/JS tanpa framework. Panel kiri berisi kartu yang diisi dari respons
  `/api/search` yang sama dengan hasil pencarian (jumlah menu, 3 menu pertama, catatan kata tak dikenal).
- Foto panel kiri opsional: taruh `hero.jpg` di `src/main/resources/assets/`. Tanpa foto, panel memakai warna emas polos.
- Versi tampilan sebelumnya ada di `backup/index.html.sebelum-desain`.
