package mieayam;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.jena.ontology.HasValueRestriction;
import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.ontology.Restriction;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;

import java.awt.Desktop;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pencarian semantik Mie Ayam. Satu kotak pencarian: kalimat pengguna dipetakan ke konsep
 * di ontologi OWL/RDF (kelas Filter*, individu, hirarki kelas) oleh SemanticParser,
 * lalu dijalankan sebagai query SPARQL di Apache Jena.
 */
public class MieAyamWeb {

    static final String NS = "http://www.semanticweb.org/legion/ontologies/2026/8/untitled-ontology-2#";
    static final String PFX = "PREFIX mie: <" + NS + ">\n"
            + "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n"
            + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n";

    static final Model model = ModelFactory.createDefaultModel();
    static SemanticParser parser;

    // ============================================================ helper
    static String label(String uri) {
        int i = uri.lastIndexOf('#');
        return (i >= 0 ? uri.substring(i + 1) : uri).replace('_', ' ');
    }

    static String local(String uri) {
        int i = uri.lastIndexOf('#');
        return i >= 0 ? uri.substring(i + 1) : uri;
    }

    static String js(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    static Map<String, List<String>> parse(String raw) {
        Map<String, List<String>> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
            m.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return m;
    }

    static String one(Map<String, List<String>> p, String key, String def) {
        List<String> l = p.get(key);
        return (l == null || l.isEmpty()) ? def : l.get(0);
    }

    // ============================================================ ontologi -> kosakata pencarian
    static void loadFile(String path) {
        model.removeAll();
        RDFDataMgr.read(model, path);
        buildVocabulary();
    }

    /**
     * Kosakata pencarian dibaca dari ontologi, bukan ditulis manual:
     * (1) kelas Filter* lewat Jena Ontology API (equivalentClass + hasValue),
     * (2) individu komponen yang dipakai menu beserta (3) kelas induknya lewat rdfs:subClassOf*.
     */
    static void buildVocabulary() {
        SemanticParser p = new SemanticParser(NS);

        OntModel om = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM, model);
        List<OntClass> classes = new ArrayList<>();
        Iterator<OntClass> it = om.listClasses();
        while (it.hasNext()) {
            OntClass oc = it.next();
            if (oc.isURIResource() && oc.getURI().startsWith(NS + "Filter")) classes.add(oc);
        }
        classes.sort((a, b) -> a.getURI().compareTo(b.getURI()));
        for (OntClass oc : classes) {
            String lbl = oc.getLabel(null);
            List<SemanticParser.Cond> conds = new ArrayList<>();
            Iterator<OntClass> eqs = oc.listEquivalentClasses();
            while (eqs.hasNext()) {
                OntClass eq = eqs.next();
                if (!eq.isIntersectionClass()) continue;
                Iterator<? extends OntClass> ops = eq.asIntersectionClass().listOperands();
                while (ops.hasNext()) {
                    OntClass op = ops.next();
                    if (!op.isRestriction()) continue;
                    Restriction rs = op.asRestriction();
                    if (!rs.isHasValueRestriction()) continue;
                    HasValueRestriction hv = rs.asHasValueRestriction();
                    RDFNode v = hv.getHasValue();
                    if (v.isURIResource()) {
                        conds.add(new SemanticParser.Cond(hv.getOnProperty().getURI(), v.asResource().getURI()));
                    }
                }
            }
            p.addFilter(lbl != null ? lbl : label(oc.getURI()), conds);
        }

        String q = PFX + "SELECT DISTINCT ?p ?o ?c WHERE {\n"
                + "  ?m a mie:MieAyam ; ?p ?o .\n"
                + "  ?o a ?d .\n"
                + "  ?d rdfs:subClassOf* ?c .\n"
                + "  FILTER(?p != rdf:type && isIRI(?o) && STRSTARTS(STR(?c), \"" + NS + "\"))\n"
                + "}";
        try (QueryExecution qe = QueryExecutionFactory.create(q, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                SemanticParser.Cond cond = new SemanticParser.Cond(s.getResource("p").getURI(), s.getResource("o").getURI());
                p.addIndividual(local(s.getResource("o").getURI()), cond);
                p.addClass(local(s.getResource("c").getURI()), cond);
            }
        }
        parser = p;
    }

    // ============================================================ eksekusi SPARQL -> JSON
    static String runJson(String query) {
        try (QueryExecution qe = QueryExecutionFactory.create(query, model)) {
            ResultSet rs = qe.execSelect();
            List<String> vars = rs.getResultVars();
            StringBuilder sb = new StringBuilder("{\"columns\":[");
            for (int i = 0; i < vars.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(js(vars.get(i)));
            }
            sb.append("],\"rows\":[");
            boolean firstRow = true;
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                if (!firstRow) sb.append(',');
                firstRow = false;
                sb.append('[');
                for (int i = 0; i < vars.size(); i++) {
                    if (i > 0) sb.append(',');
                    RDFNode n = s.get(vars.get(i));
                    if (n == null) {
                        sb.append("\"\"");
                    } else if (n.isURIResource()) {
                        String u = n.asResource().getURI();
                        sb.append(js(i == 0 ? local(u) : label(u)));
                    } else if (n.isLiteral()) {
                        Object val = n.asLiteral().getValue();
                        String txt = String.valueOf(val);
                        if (val instanceof Number && txt.matches("-?\\d+(\\.\\d+)?")) sb.append(txt);
                        else sb.append(js(n.asLiteral().getLexicalForm()));
                    } else {
                        sb.append(js(n.toString()));
                    }
                }
                sb.append(']');
            }
            return sb.append("]}").toString();
        }
    }

    // ============================================================ endpoint
    static String searchJson(String q) {
        if (q.length() > 200) q = q.substring(0, 200);
        SemanticParser.Result r = parser.parse(q);
        String res = runJson(r.sparql());

        StringBuilder sb = new StringBuilder("{\"query\":").append(js(r.sparql())).append(",\"understood\":[");
        boolean first = true;
        for (SemanticParser.Understood u : r.understood()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"text\":").append(js(u.text())).append(",\"neg\":").append(u.negated()).append(",\"group\":").append(u.group()).append(",\"via\":[");
            for (int i = 0; i < u.via().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(js(u.via().get(i)));
            }
            sb.append("]}");
        }
        sb.append("],\"unknown\":[");
        first = true;
        for (SemanticParser.Keyword k : r.unknown()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"text\":").append(js(k.text())).append(",\"neg\":").append(k.negated()).append(",\"group\":").append(k.group()).append('}');
        }
        return sb.append("],").append(res.substring(1)).toString();
    }

    static String detailJson(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_\\-.]+")) throw new IllegalArgumentException("ID tidak valid");
        Resource r = model.getResource(NS + id);
        Map<String, List<String>> props = new TreeMap<>();
        StmtIterator it = r.listProperties();
        while (it.hasNext()) {
            Statement st = it.next();
            if (st.getPredicate().equals(RDF.type)) continue;
            RDFNode o = st.getObject();
            String v = o.isLiteral() ? o.asLiteral().getLexicalForm() : (o.isURIResource() ? label(o.asResource().getURI()) : o.toString());
            props.computeIfAbsent(st.getPredicate().getLocalName(), k -> new ArrayList<>()).add(v);
        }
        StringBuilder sb = new StringBuilder("{\"title\":").append(js(label(r.getURI()))).append(",\"props\":[");
        boolean first = true;
        for (Map.Entry<String, List<String>> e : props.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"k\":").append(js(e.getKey())).append(",\"v\":[");
            for (int i = 0; i < e.getValue().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(js(e.getValue().get(i)));
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    // ============================================================ server
    interface H { String handle(HttpExchange ex) throws Exception; }

    static void send(HttpExchange ex, int code, String type, byte[] body) throws java.io.IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    static void api(HttpServer s, String path, H h) {
        s.createContext(path, ex -> {
            int code = 200;
            String out;
            try {
                out = h.handle(ex);
            } catch (Exception e) {
                code = 400;
                out = "{\"error\":" + js(String.valueOf(e.getMessage())) + "}";
            }
            send(ex, code, "application/json; charset=utf-8", out.getBytes(StandardCharsets.UTF_8));
        });
    }

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "MieAyam_lengkap.rdf";
        if (!new File(path).exists()) {
            System.err.println("File RDF tidak ditemukan: " + new File(path).getAbsolutePath());
            System.err.println("Jalankan dari folder proyek, atau beri path RDF sebagai argumen.");
            return;
        }
        loadFile(path);
        System.out.println("Ontologi dimuat: " + path + " (" + model.size() + " triple, "
                + parser.vocabularySize() + " kata kunci semantik)");

        HttpServer server = null;
        int port = 8080;
        for (; port < 8090 && server == null; port++) {
            try { server = HttpServer.create(new InetSocketAddress("localhost", port), 0); }
            catch (java.net.BindException e) { /* coba port berikutnya */ }
        }
        if (server == null) { System.err.println("Tidak ada port kosong (8080-8089)."); return; }
        port--;

        api(server, "/api/search", ex -> searchJson(one(parse(ex.getRequestURI().getRawQuery()), "q", "")));
        api(server, "/api/detail", ex -> detailJson(one(parse(ex.getRequestURI().getRawQuery()), "id", null)));
        server.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            if (p.startsWith("/assets/") && !p.contains("..") && p.substring(8).matches("[A-Za-z0-9_.\\-]+")) {
                try (InputStream in = MieAyamWeb.class.getResourceAsStream(p)) {      // foto panel: assets/hero.jpg
                    if (in != null) {
                        String n = p.toLowerCase();
                        String type = n.endsWith(".png") ? "image/png" : n.endsWith(".webp") ? "image/webp" : "image/jpeg";
                        send(ex, 200, type, in.readAllBytes());
                        return;
                    }
                }
            }
            if (!p.equals("/") && !p.equals("/index.html")) {
                send(ex, 404, "text/plain; charset=utf-8", "404".getBytes(StandardCharsets.UTF_8));
                return;
            }
            try (InputStream in = MieAyamWeb.class.getResourceAsStream("/index.html")) {
                send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
            }
        });
        server.start();

        String url = "http://localhost:" + port + "/";
        System.out.println("Server berjalan: " + url + "   (Stop untuk menghentikan)");
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception ignored) { }
    }
}
