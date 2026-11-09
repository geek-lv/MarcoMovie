package com.pilipala.marcomovie;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 电视端局域网配置接收器：手机扫码拿到地址后，把本地配置快照（JSON）POST 过来，本机整体应用。
 *
 * <p>安全骨架与 {@link ConfigTransferServer} 对齐：随机 token 路径、仅允许站点本地地址、
 * 校验 Host 头、5 分钟过期。区别在于接受 {@code application/json} 请求体（上限 8MB，容纳特征图）。
 */
final class ConfigSyncServer implements java.io.Closeable {

    interface Applier {
        void apply(String json) throws Exception;
    }

    final ServerSocket socket;
    final String path;
    final long expires;
    final Applier applier;
    volatile boolean closed;
    final AtomicBoolean applying = new AtomicBoolean();
    final ThreadPoolExecutor requests =
            new ThreadPoolExecutor(
                    0,
                    2,
                    10,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "movie-sync-request");
                        t.setDaemon(true);
                        return t;
                    });

    ConfigSyncServer(Applier applier) throws IOException {
        this(applier, TimeUnit.MINUTES.toMillis(5), new InetSocketAddress("0.0.0.0", 0));
    }

    ConfigSyncServer(Applier applier, long ttl, InetSocketAddress bind) throws IOException {
        this.applier = applier;
        this.expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ttl);
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder key = new StringBuilder();
        for (byte b : bytes) key.append(String.format(Locale.ROOT, "%02x", b & 255));
        path = "/sync/" + key;
        socket = new ServerSocket();
        socket.bind(bind);
        Thread accept =
                new Thread(
                        () -> {
                            while (!closed)
                                try {
                                    Socket client = socket.accept();
                                    try {
                                        requests.execute(() -> serve(client));
                                    } catch (RejectedExecutionException full) {
                                        client.close();
                                    }
                                } catch (IOException error) {
                                    if (!closed) close();
                                }
                        },
                        "movie-sync-listener");
        accept.setDaemon(true);
        accept.start();
    }

    String address(String host) {
        return "http://" + host + ":" + socket.getLocalPort() + path;
    }

    boolean expired() {
        return closed || System.nanoTime() >= expires;
    }

    void serve(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(30000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            InetAddress peer = s.getInetAddress();
            if (!peer.isSiteLocalAddress() && !peer.isLoopbackAddress()) {
                reply(out, 403, "仅允许局域网连接");
                return;
            }
            String[] first = line(in, 8192).split(" ");
            if (first.length != 3) return;
            Map<String, String> headers = new HashMap<>();
            int total = 0;
            for (; ; ) {
                String row = line(in, 8192);
                total += row.length();
                if (total > 32768) throw new IOException("Header limit");
                if (row.isEmpty()) break;
                int colon = row.indexOf(':');
                if (colon > 0)
                    headers.put(
                            row.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            row.substring(colon + 1).trim());
            }
            if (!first[1].equals(path)) {
                reply(out, 404, "页面不存在");
                return;
            }
            if (expired()) {
                reply(out, 410, "二维码已过期，请在手机上重新打开电视同步码");
                return;
            }
            String host = headers.get("host");
            String expected = s.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort();
            if (host == null || !host.equals(expected)) {
                reply(out, 403, "请使用电视上的二维码地址");
                return;
            }
            if (!first[0].equals("POST")) {
                reply(out, 405, "请使用手机扫码后推送");
                return;
            }
            String ct = headers.get("content-type");
            if (ct == null || !ct.toLowerCase(Locale.ROOT).contains("application/json")) {
                reply(out, 415, "仅接受 JSON");
                return;
            }
            int length;
            try {
                length = Integer.parseInt(headers.getOrDefault("content-length", "-1"));
            } catch (NumberFormatException e) {
                length = -1;
            }
            if (length < 0 || length > 8 * 1024 * 1024) {
                reply(out, 413, "配置过大");
                return;
            }
            byte[] body = new byte[length];
            for (int n = 0; n < length; ) {
                int got = in.read(body, n, length - n);
                if (got < 0) throw new EOFException();
                n += got;
            }
            if (!applying.compareAndSet(false, true)) {
                reply(out, 409, "正在接收，请稍候");
                return;
            }
            try {
                if (expired()) throw new IOException();
                applier.apply(new String(body, "UTF-8"));
                reply(out, 200, "ok");
            } catch (Exception error) {
                reply(out, 400, "同步失败：" + error.getMessage());
            } finally {
                applying.set(false);
            }
        } catch (Exception ignored) {
        }
    }

    static String line(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > max) throw new IOException("Line limit");
        }
        return b.toString("ISO-8859-1");
    }

    static void reply(OutputStream out, int code, String text) throws IOException {
        byte[] body = text.getBytes("UTF-8");
        String head =
                "HTTP/1.1 "
                        + code
                        + " OK\r\nConnection: close\r\nCache-Control: no-store\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
                        + body.length
                        + "\r\n\r\n";
        out.write(head.getBytes("US-ASCII"));
        out.write(body);
        out.flush();
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        requests.shutdownNow();
    }
}
