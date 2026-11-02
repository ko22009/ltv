package com.lmd.dpi;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Automatic DPI bypass without user settings. Runs a bundled ByeDPI (libciadpi.so) on localhost
 * and installs a default ProxySelector: each HTTPS host is probed once; if a direct TLS handshake
 * is cut by the network but works through ByeDPI, its connections go through it, otherwise direct.
 */
public final class DpiBypass {
    private static final String TAG = "LmdDpi: ";
    /** Last resort: the HTTP proxy of LazyMedia (used with its developer's permission); switched in the settings. */
    public static volatile boolean appProxyAllowed = true;
    /** The same proxy for the torrent sites (rutor and its mirrors); a separate switch, as in LazyMedia. */
    public static volatile boolean appProxyTorrentsAllowed = true;
    /** Development only: use a ByeDPI that already runs elsewhere (for example on the PC for an emulator). */
    public static volatile String externalSocksHost = null;
    public static volatile int externalSocksPort = 0;
    private static final String HOST = "127.0.0.1";
    private static final int SOCKS_PORT = 18080;
    private static final int CONNECT_PORT = 18081;
    // desync tricks tried in turn on the first launch (ciadpi options); the one that works on this network is remembered
    private static final String[][] STRATEGIES = {
            {"--disorder", "1+s"},
            {"--split", "1+s"},
            {"--tlsrec", "1+s"},
            {"--oob", "1+s"},
            {"--fake", "-1", "--ttl", "8"},
            {"--disorder", "1", "--tlsrec", "3+s"},
    };
    private static final String[] TEST_HOSTS = {"rutor.info", "rutor.is"};
    private static volatile String[] strategy = STRATEGIES[0];
    /** File where the working strategy index is kept; set by the app before start(). */
    public static volatile File strategyFile;
    private static final int DIRECT_TIMEOUT_MS = 3000;
    private static final int DPI_TIMEOUT_MS = 4000;
    private static final long TTL_DIRECT_MS = 20 * 60 * 1000L;
    private static final long TTL_DPI_MS = 60 * 60 * 1000L;
    private static final long TTL_FAILED_MS = 2 * 60 * 1000L;

    private static final ExecutorService POOL = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "lmd-dpi");
            t.setDaemon(true);
            return t;
        }
    });
    private static final ConcurrentHashMap<String, Decision> CACHE = new ConcurrentHashMap<String, Decision>();
    private static final ConcurrentHashMap<String, FutureTask<Decision>> PENDING =
            new ConcurrentHashMap<String, FutureTask<Decision>>();
    private static final List<Proxy> DIRECT = Collections.singletonList(Proxy.NO_PROXY);
    private static final Proxy VIA_DPI = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(HOST, CONNECT_PORT));
    // the app's own fallback HTTP proxy for RU users, tried last
    private static final String APP_PROXY_HOST = "n.thenewone.lol";
    private static final int APP_PROXY_PORT = 29976;
    // last known address, used when the system resolver cannot find the proxy name
    private static final String APP_PROXY_FALLBACK_IP = "185.14.30.185";
    private static volatile InetSocketAddress appProxyAddress;

    private static InetSocketAddress appProxy() {
        InetSocketAddress a = appProxyAddress;
        if (a == null) {
            try {
                a = new InetSocketAddress(InetAddress.getByName(APP_PROXY_HOST), APP_PROXY_PORT);
            } catch (Exception e) {
                try {
                    a = new InetSocketAddress(InetAddress.getByName(APP_PROXY_FALLBACK_IP), APP_PROXY_PORT);
                } catch (Exception ignored) {
                    return InetSocketAddress.createUnresolved(APP_PROXY_HOST, APP_PROXY_PORT);
                }
            }
            appProxyAddress = a;
        }
        return a;
    }

    private static String libDir;
    private static Process process;
    private static long lastStart;
    private static boolean started;

    private static final class Decision {
        final Proxy proxy;
        final long expires;

        Decision(Proxy proxy, long ttl) {
            this.proxy = proxy;
            this.expires = System.currentTimeMillis() + ttl;
        }
    }

    private DpiBypass() {
    }

    /** Called once from Application.onCreate; never throws. */
    public static synchronized void start(String nativeLibraryDir) {
        if (started) {
            return;
        }
        try {
            libDir = nativeLibraryDir;
            if (!new File(libDir, "libciadpi.so").canExecute()) {
                log("libciadpi.so not found, bypass disabled");
                return;
            }
            started = true;
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    serveConnect();
                }
            });
            ensureProcess();
            ProxySelector.setDefault(new Selector(ProxySelector.getDefault()));
            log("started");
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    tune(false);
                }
            });
        } catch (Throwable e) {
            log("start failed: " + e);
        }
    }

    /** Picks the strategy again (settings button "find a bypass"). */
    public static void retune() {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                tune(true);
            }
        });
    }

    private static int savedStrategy() {
        java.io.FileInputStream in = null;
        try {
            File f = strategyFile;
            if (f == null || !f.exists()) {
                return -1;
            }
            in = new java.io.FileInputStream(f);
            byte[] data = new byte[(int) f.length()];
            int read = in.read(data);
            int i = Integer.parseInt(new String(data, 0, Math.max(read, 0)).trim());
            return i >= 0 && i < STRATEGIES.length ? i : -1;
        } catch (Throwable e) {
            return -1;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void saveStrategy(int index) {
        try {
            File f = strategyFile;
            if (f != null) {
                java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                out.write(String.valueOf(index).getBytes());
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void restartWith(String[] next) {
        strategy = next;
        if (process != null) {
            process.destroy();
            process = null;
        }
        lastStart = 0;
        ensureProcess();
    }

    /** Tries the strategies one by one against a blocked site and keeps the first that gets through. */
    private static void tune(boolean force) {
        if (externalSocksHost != null || !started) {
            return;
        }
        if (!force) {
            int saved = savedStrategy();
            if (saved >= 0) {
                strategy = STRATEGIES[saved];
                restartWith(strategy);
                log("strategy " + saved + " restored");
                return;
            }
        }
        boolean blocked = false;
        for (String host : TEST_HOSTS) {
            if (handshake(host, null, DIRECT_TIMEOUT_MS) == V_BLOCKED) {
                blocked = true;
            }
        }
        if (!blocked) {
            log("no blocking seen, keeping the default strategy");
            return;
        }
        for (int i = 0; i < STRATEGIES.length; i++) {
            restartWith(STRATEGIES[i]);
            for (String host : TEST_HOSTS) {
                if (handshake(host, new InetSocketAddress(HOST, CONNECT_PORT), DPI_TIMEOUT_MS) == V_OK) {
                    log("strategy " + i + " works against " + host);
                    saveStrategy(i);
                    CACHE.clear();
                    return;
                }
            }
            log("strategy " + i + " did not help");
        }
        restartWith(STRATEGIES[0]);
        CACHE.clear();
        log("no strategy helped, the app proxy remains the fallback");
    }

    private static void log(String message) {
        System.err.println(TAG + message);
    }

    private static synchronized boolean ensureProcess() {
        if (externalSocksHost != null) {
            return true;
        }
        try {
            if (process != null) {
                try {
                    process.exitValue();
                } catch (IllegalThreadStateException alive) {
                    return true;
                }
            }
            if (System.currentTimeMillis() - lastStart < 30000) {
                return false;
            }
            lastStart = System.currentTimeMillis();
            List<String> cmd = new ArrayList<String>();
            cmd.add(new File(libDir, "libciadpi.so").getPath());
            cmd.add("-i");
            cmd.add(HOST);
            cmd.add("-p");
            cmd.add(String.valueOf(SOCKS_PORT));
            Collections.addAll(cmd, strategy);
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final InputStream out = process.getInputStream();
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] buf = new byte[512];
                        while (out.read(buf) >= 0) {
                            // drain so the child never blocks on a full pipe
                        }
                    } catch (IOException ignored) {
                    }
                }
            });
            Thread.sleep(300);
            try {
                log("ciadpi exited at once with " + process.exitValue());
                return false;
            } catch (IllegalThreadStateException running) {
                log("ciadpi running");
            }
            return true;
        } catch (Throwable e) {
            log("ciadpi start failed: " + e);
            return false;
        }
    }

    private static boolean isLocal(String host) {
        if (host.equals("localhost") || host.indexOf(':') >= 0 || host.endsWith(".local")) {
            return true;
        }
        if (Character.isDigit(host.charAt(host.length() - 1))) {
            return true; // IPv4 literal: nothing to probe by name (LAN, TorrServer, CDN ip)
        }
        return false;
    }

    private static final class Selector extends ProxySelector {
        private final ProxySelector previous;

        Selector(ProxySelector previous) {
            this.previous = previous;
        }

        @Override
        public List<Proxy> select(URI uri) {
            try {
                String host = uri.getHost();
                int port = uri.getPort();
                if ("https".equalsIgnoreCase(uri.getScheme()) && host != null && !isLocal(host)
                        && (port == -1 || port == 443)) {
                    Proxy p = decide(host.toLowerCase()).proxy;
                    return p == null ? DIRECT : Collections.singletonList(p);
                }
            } catch (Throwable e) {
                log("select failed: " + e);
            }
            return previous != null ? previous.select(uri) : DIRECT;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            if (sa instanceof InetSocketAddress && uri.getHost() != null
                    && (((InetSocketAddress) sa).getPort() == CONNECT_PORT
                    || ((InetSocketAddress) sa).getPort() == APP_PROXY_PORT)) {
                CACHE.remove(uri.getHost().toLowerCase());
            } else if (previous != null) {
                previous.connectFailed(uri, sa, ioe);
            }
        }
    }

    private static Decision decide(final String host) throws Exception {
        Decision cached = CACHE.get(host);
        if (cached != null && cached.expires > System.currentTimeMillis()) {
            return cached;
        }
        FutureTask<Decision> task = new FutureTask<Decision>(new Callable<Decision>() {
            @Override
            public Decision call() {
                Decision d = probe(host);
                CACHE.put(host, d);
                log(host + " -> " + (d.proxy == null ? "direct" : String.valueOf(d.proxy.address())));
                return d;
            }
        });
        FutureTask<Decision> existing = PENDING.putIfAbsent(host, task);
        if (existing == null) {
            try {
                task.run();
            } finally {
                PENDING.remove(host);
            }
            return task.get();
        }
        return existing.get();
    }

    private static Decision probe(String host) {
        int direct = handshake(host, null, DIRECT_TIMEOUT_MS);
        log(host + " direct handshake: " + direct);
        if (direct != V_BLOCKED) {
            return new Decision(null, TTL_DIRECT_MS);
        }
        if (ensureProcess()) {
            int dpi = handshake(host, new InetSocketAddress(HOST, CONNECT_PORT), DPI_TIMEOUT_MS);
            log(host + " direct blocked, via dpi: " + dpi);
            if (dpi == V_OK) {
                return new Decision(VIA_DPI, TTL_DPI_MS);
            }
        }
        if (!(host.contains("rutor") ? appProxyTorrentsAllowed : appProxyAllowed)) {
            return new Decision(null, TTL_FAILED_MS);
        }
        int app = handshake(host, appProxy(), DPI_TIMEOUT_MS);
        log(host + " via app proxy: " + app);
        return app == V_OK ? new Decision(new Proxy(Proxy.Type.HTTP, appProxy()), TTL_DPI_MS) : new Decision(null, TTL_FAILED_MS);
    }

    private static boolean usable(InetAddress a) {
        return a instanceof Inet4Address && !a.isAnyLocalAddress() && !a.isLoopbackAddress()
                && !a.isSiteLocalAddress() && !a.isLinkLocalAddress();
    }

    /** System DNS only, given 3 seconds; null when the name is not resolved or points to a stub address. */
    private static InetAddress resolveSystem(final String host) {
        FutureTask<InetAddress> task = new FutureTask<InetAddress>(new Callable<InetAddress>() {
            @Override
            public InetAddress call() throws Exception {
                for (InetAddress a : InetAddress.getAllByName(host)) {
                    if (usable(a)) {
                        return a;
                    }
                }
                return null;
            }
        });
        POOL.execute(task);
        try {
            return task.get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            task.cancel(true);
            return null;
        }
    }

    private static final ConcurrentHashMap<String, InetAddress> DOH_CACHE = new ConcurrentHashMap<String, InetAddress>();

    /** System DNS first; when it fails or lies, ask a public resolver over HTTPS (JSON API). */
    private static InetAddress resolve(String host) {
        InetAddress system = resolveSystem(host);
        if (system != null) {
            return system;
        }
        InetAddress cached = DOH_CACHE.get(host);
        if (cached != null) {
            return cached;
        }
        String[] endpoints = {"https://1.1.1.1/dns-query?name=", "https://8.8.8.8/resolve?name="};
        for (String endpoint : endpoints) {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL(endpoint + host + "&type=A").openConnection(Proxy.NO_PROXY);
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                c.setRequestProperty("Accept", "application/dns-json");
                InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                for (int n; (n = in.read(buf)) > 0; ) {
                    body.write(buf, 0, n);
                }
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"type\":1,[^}]*?\"data\":\"([0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+)\"").matcher(body.toString("UTF-8"));
                while (m.find()) {
                    InetAddress a = InetAddress.getByName(m.group(1));
                    if (usable(a)) {
                        DOH_CACHE.put(host, a);
                        log(host + " resolved over https: " + a.getHostAddress());
                        return a;
                    }
                }
            } catch (Exception e) {
                log("doh failed (" + endpoint + "): " + e);
            }
        }
        return null;
    }

    private static final int V_OK = 0, V_BLOCKED = 1, V_OTHER = 2;

    /**
     * OK: server answered the TLS hello and actually started sending data. BLOCKED: silence or reset, either
     * during the handshake or right after it — some networks let TLS through but throttle the data transfer
     * itself (seen on video CDNs), which looks identical to an app waiting forever for a stream to start.
     * OTHER: dns/refused, not a DPI symptom.
     */
    private static int handshake(String host, InetSocketAddress connectProxy, int timeoutMs) {
        Socket raw = new Socket();
        try {
            raw.setSoTimeout(timeoutMs);
            if (connectProxy == null) {
                InetAddress address = resolveSystem(host);
                if (address == null) {
                    return V_BLOCKED; // the name does not resolve, or resolves to a stub: DNS is being blocked
                }
                raw.connect(new InetSocketAddress(address, 443), timeoutMs);
            } else {
                raw.connect(connectProxy, 2000);
                OutputStream out = raw.getOutputStream();
                out.write(("CONNECT " + host + ":443 HTTP/1.1\r\nHost: " + host + ":443\r\n\r\n").getBytes("US-ASCII"));
                out.flush();
                String status = readHead(raw.getInputStream());
                if (!status.startsWith("HTTP/1.1 200") && !status.startsWith("HTTP/1.0 200")) {
                    return V_OTHER;
                }
            }
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, host, 443, true);
            ssl.setSoTimeout(timeoutMs);
            ssl.startHandshake();
            OutputStream req = ssl.getOutputStream();
            req.write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
            req.flush();
            return ssl.getInputStream().read() < 0 ? V_BLOCKED : V_OK;
        } catch (SSLException e) {
            log(host + " ssl: " + e);
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof GeneralSecurityException) {
                    return V_OK; // answered, certificate is a separate matter
                }
            }
            return V_BLOCKED;
        } catch (SocketTimeoutException e) {
            return V_BLOCKED;
        } catch (SocketException e) {
            String m = String.valueOf(e.getMessage()).toLowerCase();
            return m.contains("reset") || m.contains("closed") || m.contains("broken pipe")
                    ? V_BLOCKED : V_OTHER;
        } catch (IOException e) {
            return V_OTHER;
        } finally {
            try {
                raw.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Reads up to the blank line ending an HTTP head; returns the first line. */
    private static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (head.length() < 4096) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            head.append((char) b);
            int n = head.length();
            if (n >= 4 && head.charAt(n - 1) == '\n' && head.charAt(n - 2) == '\r'
                    && head.charAt(n - 3) == '\n' && head.charAt(n - 4) == '\r') {
                break;
            }
        }
        int eol = head.indexOf("\r\n");
        return eol < 0 ? head.toString() : head.substring(0, eol);
    }

    /** Local HTTP CONNECT front-end: resolves names with the system DNS (ciadpi cannot on Android). */
    private static void serveConnect() {
        try {
            ServerSocket server = new ServerSocket(CONNECT_PORT, 50, InetAddress.getByName(HOST));
            while (true) {
                final Socket client = server.accept();
                POOL.execute(new Runnable() {
                    @Override
                    public void run() {
                        tunnel(client);
                    }
                });
            }
        } catch (IOException e) {
            log("connect server stopped: " + e);
        }
    }

    private static void tunnel(final Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(15000);
            String line = readHead(client.getInputStream());
            String[] parts = line.split(" ");
            if (parts.length < 2 || !parts[0].equals("CONNECT")) {
                client.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes("US-ASCII"));
                return;
            }
            int colon = parts[1].lastIndexOf(':');
            String host = parts[1].substring(0, colon);
            int port = Integer.parseInt(parts[1].substring(colon + 1));
            InetAddress target = resolve(host);
            if (target == null) {
                throw new IOException("no IPv4 for " + host);
            }
            upstream = new Socket(new Proxy(Proxy.Type.SOCKS, externalSocksHost != null
                    ? new InetSocketAddress(externalSocksHost, externalSocksPort) : new InetSocketAddress(HOST, SOCKS_PORT)));
            upstream.connect(new InetSocketAddress(target, port), 8000);
            client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes("US-ASCII"));
            client.getOutputStream().flush();
            client.setSoTimeout(0);
            final Socket up = upstream;
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    pipe(up, client);
                }
            });
            pipe(client, upstream);
            return;
        } catch (Exception e) {
            try {
                client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes("US-ASCII"));
            } catch (IOException ignored) {
            }
        }
        closeQuietly(client);
        closeQuietly(upstream);
    }

    private static void pipe(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
