package mieayam;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mengubah kalimat pencarian (mis. "halal pedas tanpa babi") menjadi konsep-konsep ontologi,
 * lalu menyusun query SPARQL-nya. Kelas ini tidak bergantung pada Jena: kosakata (vocabulary)
 * diisi dari luar oleh MieAyamWeb yang membaca ontologi RDF.
 *
 * Sumber kosakata, dari yang paling berwenang:
 *  1. kelas Filter*  (owl:equivalentClass + hasValue)  -> "halal", "kuah bening", "berbakso", ...
 *  2. individu komponen (Bakso_Sapi, Sawi_Hijau, ...)   -> "bakso sapi", "sawi hijau", "bakso", ...
 *  3. kelas komponen (OlahanBabi, OlahanDaging, ...)    -> "babi", "daging", "sayur", ... (lewat rdfs:subClassOf)
 */
public class SemanticParser {

    /** Satu syarat: ?menu <prop> <value>. */
    public record Cond(String prop, String value) { }

    /** Konsep ontologi. Terpenuhi bila salah satu alternatif terpenuhi; satu alternatif = semua Cond terpenuhi. */
    public static final class Concept {
        final boolean locked;
        final List<List<Cond>> alts = new ArrayList<>();
        final Set<String> viaClass = new LinkedHashSet<>();
        final Set<String> viaOther = new LinkedHashSet<>();

        Concept(boolean locked) { this.locked = locked; }

        List<String> via() {
            Set<String> all = new LinkedHashSet<>(viaClass);
            all.addAll(viaOther);
            return new ArrayList<>(all);
        }
    }

    /** Konsep yang berhasil dikenali dari kalimat pencarian. */
    public record Understood(String text, boolean negated, List<String> via, int group) { }

    /** Kata yang tidak ada di ontologi; dicari pada nama menu. */
    public record Keyword(String text, boolean negated, int group) { }

    public record Result(List<Understood> understood, List<Keyword> unknown, String sparql) { }

    /** Syarat kalori: ekspresi FILTER SPARQL + teks ringkas untuk ditampilkan. */
    private record Cal(String cond, String show) { }

    private record CalMatch(Cal cal, int len) { }

    /** Pembanding kalori, mis. "di bawah" -> "<". */
    private record Cmp(String op, String... words) { }

    private record Hit(String text, Concept concept, Cal cal, boolean negated, int group) { }

    // ------------------------------------------------------------------ kata bantu
    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "yang", "yg", "dan", "atau", "dengan", "dgn", "di", "ke", "dari", "untuk", "ada", "pakai", "pake",
            "menu", "mie", "cari", "carikan", "tampilkan", "tolong", "saya", "aku", "mau", "pengen", "ingin",
            "apa", "saja", "aja", "semua", "non", "sama", "itu", "ini", "yg", "dong", "deh", "kalori"));
    private static final Set<String> CONJ = new HashSet<>(Arrays.asList("dan", "atau", "maupun", "serta"));
    private static final Set<String> NEG = new HashSet<>(Arrays.asList(
            "tanpa", "bukan", "tidak", "nggak", "gak", "ga", "jangan", "bebas", "minus", "kecuali"));
    /** Kata awalan nama kelas/individu yang boleh dibuang agar "Olahan Babi" juga dikenal sebagai "babi". */
    private static final Set<String> QUALIFIERS = new HashSet<>(Arrays.asList(
            "olahan", "jenis", "sertifikasi", "kelompok", "rasa", "texture", "warna", "kuah"));
    /** Awalan label kelas Filter: "Filter Rasa Pedas" -> "rasa pedas" dan "pedas". */
    private static final Set<String> FILTER_PREFIX = new HashSet<>(Arrays.asList("rasa", "kuah", "mie", "jenis"));
    /** Kelas induk yang terlalu umum untuk dijadikan kata pencarian. */
    private static final Set<String> SKIP_CLASS = new HashSet<>(Arrays.asList(
            "komponen", "karakter kuah", "kelompok", "sertifikasi", "mie ayam", "jenis mie"));
    /** Sinonim sederhana (kata -> frasa yang ada di ontologi). */
    private static final Map<String, String> SYN = Map.of(
            "haram", "non halal",
            "vegan", "vegetarian",
            "sayuran", "sayur",
            "pedes", "pedas",
            "kkal", "kalori",
            "kcal", "kalori",
            "calorie", "kalori",
            "calories", "kalori",
            "kalorinya", "kalori");

    // ------------------------------------------------------------------ kalori
    /**
     * Ontologi hanya menyimpan angka mie:kalori per menu (tidak ada kelas Filter untuk kalori),
     * jadi "rendah"/"tinggi" didefinisikan di sini. Ubah dua angka ini bila batasnya ingin diganti.
     */
    static final int LOW_MAX = 500;
    static final int HIGH_MIN = 700;

    private static final String LE = "\u2264 ", GE = "\u2265 ";
    private static final Cal LOW = new Cal("?kalori <= " + LOW_MAX, LE + LOW_MAX + " kkal");
    private static final Cal HIGH = new Cal("?kalori >= " + HIGH_MIN, GE + HIGH_MIN + " kkal");

    private static final Map<String, Cal> CAL_PHRASE = Map.ofEntries(
            Map.entry("diet", LOW), Map.entry("rendah kalori", LOW), Map.entry("kalori rendah", LOW),
            Map.entry("low kalori", LOW), Map.entry("sedikit kalori", LOW), Map.entry("kalori sedikit", LOW),
            Map.entry("kalori kecil", LOW), Map.entry("hemat kalori", LOW),
            Map.entry("tinggi kalori", HIGH), Map.entry("kalori tinggi", HIGH), Map.entry("high kalori", HIGH),
            Map.entry("banyak kalori", HIGH), Map.entry("kalori banyak", HIGH), Map.entry("kalori besar", HIGH));

    private static final List<Cmp> CMP = List.of(
            new Cmp("<", "kurang", "dari"), new Cmp("<", "di", "bawah"), new Cmp("<", "dibawah"),
            new Cmp("<", "under"), new Cmp("<", "below"),
            new Cmp("<=", "maksimal"), new Cmp("<=", "maksimum"), new Cmp("<=", "maks"), new Cmp("<=", "max"),
            new Cmp("<=", "sampai", "dengan"), new Cmp("<=", "sampai"), new Cmp("<=", "hingga"),
            new Cmp("<=", "paling", "banyak"),
            new Cmp(">", "lebih", "dari"), new Cmp(">", "di", "atas"), new Cmp(">", "diatas"),
            new Cmp(">", "over"), new Cmp(">", "above"),
            new Cmp(">=", "minimal"), new Cmp(">=", "minimum"), new Cmp(">=", "min"),
            new Cmp(">=", "paling", "sedikit"));

    /** Akhiran setelah angka: "500 kalori ke bawah". */
    private static final List<Cmp> CMP_AFTER = List.of(
            new Cmp("<=", "ke", "bawah"), new Cmp("<=", "kebawah"), new Cmp("<=", "atau", "kurang"),
            new Cmp("<=", "atau", "lebih", "sedikit"),
            new Cmp(">=", "ke", "atas"), new Cmp(">=", "keatas"), new Cmp(">=", "atau", "lebih"));

    private final String ns;
    private final Map<String, Concept> vocab = new HashMap<>();
    private int maxWords = 1;

    public SemanticParser(String ns) { this.ns = ns; }

    public int vocabularySize() { return vocab.size(); }

    // ------------------------------------------------------------------ normalisasi
    static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** "Daging_Babi_Cincang" -> "daging babi cincang"; "MIeKriting" -> "mie kriting". */
    static String words(String local) {
        return norm(local.replace('_', ' ').replaceAll("(?<=[a-z])(?=[A-Z])", " "));
    }

    static String title(String w) {
        StringBuilder sb = new StringBuilder();
        for (String x : w.split(" ")) {
            if (x.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(x.charAt(0))).append(x.substring(1));
        }
        return sb.toString();
    }

    private static String from(String[] w, int start) {
        return String.join(" ", Arrays.copyOfRange(w, start, w.length));
    }

    // ------------------------------------------------------------------ mengisi kosakata
    private void put(String phrase, Concept c) {
        if (phrase.isEmpty() || vocab.containsKey(phrase)) return;
        vocab.put(phrase, c);
        maxWords = Math.max(maxWords, phrase.split(" ").length);
    }

    /** Ambil konsep untuk frasa; null bila frasa itu sudah dikunci oleh kelas Filter. */
    private Concept mergeTarget(String phrase) {
        Concept c = vocab.get(phrase);
        if (c == null) {
            c = new Concept(false);
            put(phrase, c);
        } else if (c.locked) {
            return null;
        }
        return c;
    }

    private static void addAlt(Concept c, Cond cond) {
        List<Cond> alt = List.of(cond);
        if (!c.alts.contains(alt)) c.alts.add(alt);
    }

    /** Kelas Filter*: label "Filter Halal" + semua hasValue pada equivalentClass-nya. */
    public void addFilter(String label, List<Cond> conditions) {
        String base = norm(label);
        if (base.startsWith("filter ")) base = base.substring(7);
        if (base.isEmpty() || conditions.isEmpty()) return;
        Concept c = new Concept(true);
        c.alts.add(List.copyOf(conditions));
        c.viaClass.add(label.trim());
        Set<String> phrases = new LinkedHashSet<>();
        phrases.add(base);
        String[] w = base.split(" ");
        if (w.length > 1) {
            if (FILTER_PREFIX.contains(w[0])) phrases.add(from(w, 1));
            phrases.add(base.replace(" ", ""));
        }
        for (String p : phrases) put(p, c);
    }

    /** Individu komponen yang dipakai menu, mis. Bakso_Sapi lewat punyaTopping. */
    public void addIndividual(String local, Cond cond) {
        String base = words(local).replaceAll(" \\d+$", "");
        if (base.isEmpty()) return;
        Set<String> phrases = new LinkedHashSet<>();
        phrases.add(base);
        String[] w = base.split(" ");
        if (w.length > 1 && QUALIFIERS.contains(w[0])) phrases.add(from(w, 1));
        for (String x : w) {
            if (x.length() >= 3 && !STOP.contains(x) && !QUALIFIERS.contains(x)) phrases.add(x);
        }
        for (String p : phrases) {
            Concept c = mergeTarget(p);
            if (c == null) continue;
            addAlt(c, cond);
            c.viaOther.add(title(base));
        }
    }

    /** Kelas komponen yang menaungi individu tsb (langsung atau lewat rdfs:subClassOf). */
    public void addClass(String local, Cond cond) {
        String base = words(local);
        if (base.isEmpty() || SKIP_CLASS.contains(base)) return;
        Set<String> phrases = new LinkedHashSet<>();
        phrases.add(base);
        String[] w = base.split(" ");
        if (w.length > 1 && QUALIFIERS.contains(w[0])) phrases.add(from(w, 1));
        for (String p : phrases) {
            Concept c = mergeTarget(p);
            if (c == null) continue;
            addAlt(c, cond);
            c.viaClass.add(title(base));
        }
    }

    // ------------------------------------------------------------------ kalori: pencocokan
    private static boolean isNum(List<String> t, int i) {
        return i < t.size() && t.get(i).matches("\\d{1,5}");
    }

    private static boolean isUnit(List<String> t, int i) {
        return i < t.size() && t.get(i).equals("kalori");
    }

    private static boolean at(List<String> t, int i, String[] w) {
        if (i + w.length > t.size()) return false;
        for (int k = 0; k < w.length; k++) if (!t.get(i + k).equals(w[k])) return false;
        return true;
    }

    private static Cmp longest(List<Cmp> list, List<String> t, int i) {
        Cmp best = null;
        for (Cmp c : list) if (at(t, i, c.words()) && (best == null || c.words().length > best.words().length)) best = c;
        return best;
    }

    private static Cal cal(String op, int n) {
        String sym = switch (op) { case "<=" -> LE; case ">=" -> GE; case "=" -> ""; default -> op + " "; };
        return new Cal("?kalori " + op + " " + n, sym + n + " kkal");
    }

    private static Cal range(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return new Cal("?kalori >= " + lo + " && ?kalori <= " + hi, lo + "\u2013" + hi + " kkal");
    }

    /**
     * Mengenali ungkapan kalori di posisi i: frasa tetap ("rendah kalori", "diet"), rentang
     * ("antara 400 dan 600 kalori", "400-600 kkal"), pembanding ("di bawah 500 kalori", "minimal 600 kkal"),
     * atau angka saja ("520 kalori"). Angka hanya dianggap kalori bila ada kata "kalori" di dekatnya.
     */
    private CalMatch calorieAt(List<String> t, int i) {
        for (int n = Math.min(2, t.size() - i); n >= 1; n--) {
            Cal c = CAL_PHRASE.get(String.join(" ", t.subList(i, i + n)));
            if (c != null) return new CalMatch(c, n);
        }
        int j = i;
        boolean unitBefore = isUnit(t, j);
        if (unitBefore) j++;

        // rentang: [antara] A (sampai|hingga|dan) B
        int k = j;
        boolean antara = k < t.size() && t.get(k).equals("antara");
        if (antara) k++;
        if (isNum(t, k)) {
            int m = k + 1, cn = 0;
            if (at(t, m, new String[]{"sampai", "dengan"})) cn = 2;
            else if (at(t, m, new String[]{"sampai"}) || at(t, m, new String[]{"hingga"})) cn = 1;
            else if (antara && at(t, m, new String[]{"dan"})) cn = 1;
            if (cn > 0 && isNum(t, m + cn)) {
                int end = m + cn + 1;
                boolean unitAfter = isUnit(t, end);
                if (unitAfter) end++;
                if (unitBefore || unitAfter) {
                    return new CalMatch(range(Integer.parseInt(t.get(k)), Integer.parseInt(t.get(m + cn))), end - i);
                }
            }
        }

        // pembanding di depan angka
        Cmp c = longest(CMP, t, j);
        int n0 = c == null ? j : j + c.words().length;
        if (c != null && isNum(t, n0)) {
            int end = n0 + 1;
            boolean unitAfter = isUnit(t, end);
            if (unitAfter) end++;
            if (unitBefore || unitAfter) return new CalMatch(cal(c.op(), Integer.parseInt(t.get(n0))), end - i);
        }

        // angka saja, boleh diikuti "ke bawah" / "ke atas"
        if (isNum(t, j)) {
            int end = j + 1;
            boolean unitAfter = isUnit(t, end);
            if (unitAfter) end++;
            if (!unitBefore && !unitAfter) return null;
            Cmp suf = longest(CMP_AFTER, t, end);
            if (suf != null) end += suf.words().length;
            return new CalMatch(cal(suf == null ? "=" : suf.op(), Integer.parseInt(t.get(j))), end - i);
        }
        return null;
    }

    // ------------------------------------------------------------------ parsing
    /**
     * "atau" memecah kalimat menjadi kelompok; di dalam satu kelompok semua syarat digabung AND,
     * antar kelompok digabung OR (UNION). Pengecualian: "tanpa babi atau telur" berarti keduanya ditolak.
     */
    public Result parse(String raw) {
        String r = raw == null ? "" : raw.toLowerCase()
                .replace("<=", " maksimal ").replace("\u2264", " maksimal ")
                .replace(">=", " minimal ").replace("\u2265", " minimal ")
                .replace("<", " kurang dari ").replace(">", " lebih dari ")
                .replaceAll("(\\d)\\s*[-\u2013\u2014~]\\s*(\\d)", "$1 sampai $2");
        String s = " " + norm(r) + " ";
        // "tidak lebih dari 500 kalori" = "maksimal 500 kalori" (dan sebaliknya), agar tidak terbaca sebagai negasi
        s = s.replaceAll(" (?:tidak|nggak|gak|ga) (?:lebih dari|di atas) (?=\\d)", " maksimal ")
             .replaceAll(" (?:tidak|nggak|gak|ga) (?:kurang dari|di bawah) (?=\\d)", " minimal ");
        while (s.contains(" mie ayam ")) s = s.replace(" mie ayam ", " ");
        s = s.trim();

        List<String> t = new ArrayList<>();
        if (!s.isEmpty()) {
            for (String tok : s.split(" ")) {
                String syn = SYN.get(tok);
                if (syn != null) t.addAll(Arrays.asList(syn.split(" ")));
                else t.add(tok);
            }
        }

        List<Hit> hits = new ArrayList<>();
        List<Keyword> unknown = new ArrayList<>();
        boolean neg = false, prevNeg = false;
        int g = 0;
        int i = 0;
        while (i < t.size()) {
            String tok = t.get(i);
            if (NEG.contains(tok)) { neg = true; i++; continue; }

            CalMatch cm = calorieAt(t, i);
            if (cm != null) {
                boolean dup = false;
                for (Hit h : hits) if (cm.cal().equals(h.cal) && h.negated == neg && h.group == g) dup = true;
                if (!dup) hits.add(new Hit(String.join(" ", t.subList(i, i + cm.len())), null, cm.cal(), neg, g));
                prevNeg = neg;
                neg = false;
                i += cm.len();
                continue;
            }

            Concept c = null;
            String matched = null;
            int len = 0;
            for (int n = Math.min(maxWords, t.size() - i); n >= 1; n--) {
                String ph = String.join(" ", t.subList(i, i + n));
                Concept x = vocab.get(ph);
                if (x != null) { c = x; matched = ph; len = n; break; }
            }
            if (c != null) {
                boolean dup = false;
                for (Hit h : hits) if (h.concept == c && h.negated == neg && h.group == g) dup = true;
                if (!dup) hits.add(new Hit(matched, c, null, neg, g));
                prevNeg = neg;
                neg = false;
                i += len;
                continue;
            }
            if (tok.equals("atau") && !prevNeg) {   // pemisah OR: mulai kelompok baru
                g++;
                neg = false;
                prevNeg = false;
                i++;
                continue;
            }
            if (CONJ.contains(tok)) {               // "tanpa babi dan telur": penolakan berlanjut
                if (prevNeg) neg = true;
                i++;
                continue;
            }
            if (STOP.contains(tok)) { i++; continue; }
            Keyword kw = new Keyword(tok, neg, g);
            if (!unknown.contains(kw)) unknown.add(kw);
            prevNeg = neg;
            neg = false;
            i++;
        }

        List<Understood> und = new ArrayList<>();
        for (Hit h : hits) und.add(new Understood(h.text, h.negated, h.cal != null ? List.of(h.cal.show()) : h.concept.via(), h.group));
        return new Result(und, unknown, sparql(hits, unknown, g + 1));
    }

    // ------------------------------------------------------------------ SPARQL
    private String term(String uri) {
        if (uri.startsWith(ns)) {
            String l = uri.substring(ns.length());
            if (l.matches("[A-Za-z0-9_]+")) return "mie:" + l;
        }
        return "<" + uri + ">";
    }

    private String conj(List<Cond> alt) {
        StringBuilder sb = new StringBuilder();
        for (Cond c : alt) sb.append("?menu ").append(term(c.prop())).append(' ').append(term(c.value())).append(" . ");
        return sb.toString();
    }

    /** Pola untuk satu kelompok (semua syarat AND). */
    private void groupBody(StringBuilder sb, List<Hit> hits, List<Keyword> kws, String ind, boolean rebindKalori) {
        boolean kaloriBound = false;
        for (Hit h : hits) {
            sb.append(ind).append("# ").append(h.negated ? "tanpa " : "").append(h.text).append('\n');
            if (h.cal != null) {
                if (rebindKalori && !kaloriBound) {
                    sb.append(ind).append("?menu mie:kalori ?kalori .\n");
                    kaloriBound = true;
                }
                sb.append(ind).append("FILTER(").append(h.negated ? "!(" + h.cal.cond() + ")" : h.cal.cond()).append(")\n");
                continue;
            }
            List<List<Cond>> alts = h.concept.alts;
            String body;
            if (alts.size() == 1) {
                body = conj(alts.get(0));
            } else {
                StringBuilder u = new StringBuilder();
                for (int i = 0; i < alts.size(); i++) {
                    if (i > 0) u.append("UNION ");
                    u.append("{ ").append(conj(alts.get(i))).append("} ");
                }
                body = u.toString();
            }
            if (h.negated) sb.append(ind).append("FILTER NOT EXISTS { ").append(body).append("}\n");
            else sb.append(ind).append(body.trim()).append('\n');
        }
        for (Keyword k : kws) {
            sb.append(ind).append("FILTER(").append(k.negated() ? "!" : "")
              .append("CONTAINS(LCASE(STR(?nama)), \"").append(k.text()).append("\"))\n");
        }
    }

    private String sparql(List<Hit> hits, List<Keyword> unknown, int groups) {
        StringBuilder sb = new StringBuilder("PREFIX mie: <" + ns + ">\n\n");
        sb.append("SELECT DISTINCT ?menu ?nama ?kalori ?pedas ?sert ?kel ?jenisMie ?gambar WHERE {\n")
          .append("  ?menu a mie:MieAyam ;\n")
          .append("        mie:namaMenu ?nama ;\n")
          .append("        mie:kalori ?kalori ;\n")
          .append("        mie:tingkatPedas ?pedas ;\n")
          .append("        mie:punyaSertifikasi ?sert ;\n")
          .append("        mie:termasukKelompok ?kel ;\n")
          .append("        mie:punyaJenisMie ?jenisMie .\n");

        // kelompok yang tidak kosong
        List<List<Hit>> gh = new ArrayList<>();
        List<List<Keyword>> gk = new ArrayList<>();
        for (int g = 0; g < groups; g++) {
            List<Hit> h = new ArrayList<>();
            List<Keyword> k = new ArrayList<>();
            for (Hit x : hits) if (x.group == g) h.add(x);
            for (Keyword x : unknown) if (x.group() == g) k.add(x);
            if (!h.isEmpty() || !k.isEmpty()) { gh.add(h); gk.add(k); }
        }

        if (gh.size() == 1) {
            groupBody(sb, gh.get(0), gk.get(0), "  ", false);
        } else if (gh.size() > 1) {
            // Tiap blok mengikat ?menu & ?nama sendiri agar FILTER di dalamnya punya variabel terikat.
            for (int g = 0; g < gh.size(); g++) {
                sb.append(g == 0 ? "  {\n" : "  UNION {\n");
                sb.append("    ?menu a mie:MieAyam ; mie:namaMenu ?nama .\n");
                groupBody(sb, gh.get(g), gk.get(g), "    ", true);
                sb.append("  }\n");
            }
        }
        boolean byKalori = false;
        for (Hit h : hits) if (h.cal != null) byKalori = true;
        sb.append("  OPTIONAL { ?menu mie:gambar ?gambar }\n");
        return sb.append("}\nORDER BY ").append(byKalori ? "?kalori ?nama" : "?nama").toString();
    }
}
