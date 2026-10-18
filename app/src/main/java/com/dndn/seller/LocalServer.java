package com.dndn.seller;

import android.content.Context;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** سيرفر HTTP صغير يعمل داخل الهاتف: الزبائن عبر الواي فاي، والبائع عبر 127.0.0.1 فقط. */
public class LocalServer {
    public static final int PORT = 8080;
    public interface Listener { void onCustomerMessage(String name, String text, boolean hasImg); }

    private final Context ctx;
    private final Listener listener;
    private final File db, imgDir;
    private ServerSocket ss;
    private volatile boolean running;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private JSONObject threads = new JSONObject();
    private final Set<OutputStream> sellers = Collections.synchronizedSet(new HashSet<OutputStream>());
    private final Map<String, Set<OutputStream>> custs = new ConcurrentHashMap<>();

    public LocalServer(Context c, Listener l) {
        ctx = c; listener = l;
        db = new File(c.getFilesDir(), "data.json");
        imgDir = new File(c.getFilesDir(), "img");
        imgDir.mkdirs();
        try { threads = new JSONObject(new String(Files.readAllBytes(db.toPath()), "UTF-8")); } catch (Exception e) { }
    }

    public void start() throws IOException {
        ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(PORT));
        running = true;
        new Thread(() -> {
            while (running) {
                try { final Socket s = ss.accept(); pool.execute(() -> handle(s)); }
                catch (IOException e) { if (!running) break; }
            }
        }).start();
    }

    public void stop() {
        running = false;
        try { ss.close(); } catch (Exception e) { }
        pool.shutdownNow();
    }

    private String sellerName() { return ctx.getSharedPreferences("cfg", 0).getString("name", "dndn"); }
    private synchronized void save() {
        try (FileOutputStream f = new FileOutputStream(db)) { f.write(threads.toString().getBytes("UTF-8")); } catch (Exception e) { }
    }

    // ---------- HTTP ----------
    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String line = readLine(in);
            if (line == null) return;
            String[] p = line.split(" ");
            if (p.length < 2) return;
            int len = 0; String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                String l = h.toLowerCase();
                if (l.startsWith("content-length:")) len = Integer.parseInt(l.substring(15).trim());
            }
            if (len > 8000000) { respond(out, 413, "text/plain", "big".getBytes()); return; }
            byte[] body = new byte[len]; int o = 0;
            while (o < len) { int r = in.read(body, o, len - o); if (r < 0) break; o += r; }
            boolean local = s.getInetAddress().isLoopbackAddress();
            String path = p[1], query = ""; int qi = path.indexOf('?');
            if (qi >= 0) { query = path.substring(qi + 1); path = path.substring(0, qi); }

            if (p[0].equals("GET")) {
                if (path.equals("/")) { respond(out, 200, "text/html; charset=utf-8", asset("customer.html")); return; }
                if (path.equals("/seller") && local) { respond(out, 200, "text/html; charset=utf-8", asset("seller.html")); return; }
                if (path.equals("/ping")) {
                    json(out, new JSONObject().put("app", "dndn").put("seller", sellerName()).toString()); return;
                }
                if (path.equals("/api/info") && local) {
                    json(out, new JSONObject().put("name", sellerName()).put("ips", new JSONArray(ips())).toString()); return;
                }
                if (path.startsWith("/img/")) {
                    String n = path.substring(5);
                    File f = new File(imgDir, n);
                    if (n.contains("/") || n.contains("..") || !f.exists()) { respond(out, 404, "text/plain", new byte[0]); return; }
                    respond(out, 200, "image/jpeg", Files.readAllBytes(f.toPath())); return;
                }
                if (path.equals("/api/stream")) { stream(s, out, query, local); return; }
                respond(out, 404, "text/plain", new byte[0]);
            } else if (p[0].equals("POST")) {
                String bs = new String(body, "UTF-8");
                JSONObject d = new JSONObject(bs.isEmpty() ? "{}" : bs);
                switch (path) {
                    case "/api/join": join(out, d); break;
                    case "/api/send": send(out, d, local); break;
                    case "/api/settings":
                        if (!local) { respond(out, 403, "text/plain", new byte[0]); break; }
                        String nm = d.optString("name", "dndn").trim();
                        if (nm.isEmpty()) nm = "dndn";
                        ctx.getSharedPreferences("cfg", 0).edit().putString("name", nm).apply();
                        json(out, "{}"); break;
                    default: respond(out, 404, "text/plain", new byte[0]);
                }
            }
        } catch (Exception e) {
        } finally { try { s.close(); } catch (Exception e) { } }
    }

    private void join(OutputStream out, JSONObject d) throws Exception {
        String name = d.optString("name", "").trim();
        if (name.length() > 40) name = name.substring(0, 40);
        if (name.isEmpty()) { respond(out, 400, "text/plain", new byte[0]); return; }
        String id = d.optString("id", "");
        String ev;
        synchronized (this) {
            JSONObject t = threads.optJSONObject(id);
            if (t == null) {
                id = Long.toString(System.nanoTime(), 36) + Integer.toString(new Random().nextInt(1296), 36);
                t = new JSONObject().put("name", name).put("msgs", new JSONArray());
                threads.put(id, t);
            } else t.put("name", name);
            save();
            ev = new JSONObject().put("id", id).put("thread", t).toString();
        }
        broadcast(sellers, "thread", ev);
        json(out, new JSONObject().put("id", id).put("seller", sellerName()).toString());
    }

    private void send(OutputStream out, JSONObject d, boolean local) throws Exception {
        String text = d.optString("text", "");
        if (text.length() > 2000) text = text.substring(0, 2000);
        String img = d.optString("img", "");
        JSONObject m = new JSONObject().put("t", System.currentTimeMillis()).put("text", text);
        List<String> targets = new ArrayList<>();
        if (local) {
            m.put("from", "s");
            String to = d.optString("to", "");
            if (to.equals("*")) { synchronized (this) { Iterator<String> it = threads.keys(); while (it.hasNext()) targets.add(it.next()); } }
            else targets.add(to);
        } else {
            m.put("from", "c");
            targets.add(d.optString("id", ""));
        }
        if (targets.isEmpty() || threads.optJSONObject(targets.get(0)) == null) { respond(out, 404, "text/plain", new byte[0]); return; }
        if (img.startsWith("data:image/")) {
            String name = "i" + System.nanoTime() + ".jpg";
            byte[] raw = Base64.decode(img.substring(img.indexOf(',') + 1), Base64.DEFAULT);
            try (FileOutputStream f = new FileOutputStream(new File(imgDir, name))) { f.write(raw); }
            m.put("img", "/img/" + name);
        }
        if (text.isEmpty() && !m.has("img")) { respond(out, 400, "text/plain", new byte[0]); return; }
        String firstName = "";
        synchronized (this) {
            for (String id : targets) {
                JSONObject t = threads.optJSONObject(id);
                if (t != null) t.getJSONArray("msgs").put(m);
            }
            save();
            JSONObject t0 = threads.optJSONObject(targets.get(0));
            if (t0 != null) firstName = t0.optString("name", "");
        }
        for (String id : targets) {
            if (threads.optJSONObject(id) == null) continue;
            Set<OutputStream> cs = custs.get(id);
            if (cs != null) broadcast(cs, "msg", m.toString());
            broadcast(sellers, "msg", new JSONObject().put("id", id).put("m", m).toString());
        }
        if (!local && listener != null) listener.onCustomerMessage(firstName, text, m.has("img"));
        json(out, "{}");
    }

    private void stream(Socket s, OutputStream out, String query, boolean local) throws Exception {
        String id = q(query, "id");
        boolean seller = local && "seller".equals(q(query, "role"));
        if (!seller && threads.optJSONObject(id) == null) { respond(out, 404, "text/plain", new byte[0]); return; }
        s.setSoTimeout(0);
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\nretry: 2000\n\n".getBytes());
        out.flush();
        String init;
        synchronized (this) {
            init = seller
                ? new JSONObject().put("threads", threads).put("online", online()).toString()
                : new JSONObject().put("seller", sellerName()).put("msgs", threads.getJSONObject(id).getJSONArray("msgs")).toString();
        }
        event(out, "init", init);
        Set<OutputStream> set = seller ? sellers
                : custs.computeIfAbsent(id, k -> Collections.synchronizedSet(new HashSet<OutputStream>()));
        set.add(out);
        if (!seller) presence();
        try {
            while (running) {
                Thread.sleep(8000);
                synchronized (out) { out.write(": \n\n".getBytes()); out.flush(); }
            }
        } catch (Exception e) {
        } finally {
            set.remove(out);
            if (!seller) presence();
        }
    }

    // ---------- أدوات ----------
    private JSONArray online() {
        JSONArray a = new JSONArray();
        for (Map.Entry<String, Set<OutputStream>> e : custs.entrySet()) if (!e.getValue().isEmpty()) a.put(e.getKey());
        return a;
    }
    private void presence() { broadcast(sellers, "presence", online().toString()); }

    private void broadcast(Set<OutputStream> set, String ev, String data) {
        List<OutputStream> l;
        synchronized (set) { l = new ArrayList<>(set); }
        for (OutputStream o : l) { try { event(o, ev, data); } catch (Exception e) { set.remove(o); } }
    }
    private void event(OutputStream o, String ev, String data) throws IOException {
        synchronized (o) { o.write(("event: " + ev + "\ndata: " + data + "\n\n").getBytes("UTF-8")); o.flush(); }
    }
    private void json(OutputStream out, String s) throws IOException { respond(out, 200, "application/json; charset=utf-8", s.getBytes("UTF-8")); }
    private void respond(OutputStream out, int code, String type, byte[] b) throws IOException {
        String head = "HTTP/1.1 " + code + " X\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n";
        out.write(head.getBytes("ISO-8859-1")); out.write(b); out.flush();
    }
    private byte[] asset(String n) throws IOException {
        InputStream i = ctx.getAssets().open(n);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int r;
        while ((r = i.read(buf)) > 0) b.write(buf, 0, r);
        i.close();
        return b.toByteArray();
    }
    private static String q(String query, String k) {
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(k)) { try { return URLDecoder.decode(kv.substring(i + 1), "UTF-8"); } catch (Exception e) { } }
        }
        return "";
    }
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(); int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("long");
        }
        return sb.length() == 0 ? null : sb.toString();
    }
    public static List<String> ips() {
        List<String> l = new ArrayList<>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String nm = n.getName();
                if (!n.isUp() || n.isLoopback()) continue;
                if (!(nm.startsWith("wlan") || nm.startsWith("ap") || nm.startsWith("swlan") || nm.startsWith("eth"))) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses()))
                    if (a instanceof Inet4Address) l.add(a.getHostAddress());
            }
        } catch (Exception e) { }
        return l;
    }
}
