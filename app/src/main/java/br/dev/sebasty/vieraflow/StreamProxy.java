package br.dev.sebasty.vieraflow;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.webkit.CookieManager;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local, ephemeral HTTP relay for media resources, cookies, Range and HLS URLs. */
public final class StreamProxy {
    private final Context app;
    private final String token = UUID.randomUUID().toString().replace("-", "");
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private ServerSocket listener;
    private String baseUrl;
    private volatile String referer = "";
    private volatile String userAgent = "VieraFlow/0.1";
    private volatile String lastRequest = "A TV não solicitou o vídeo ao celular.";
    public void resetDiagnostic() { lastRequest = "A TV não solicitou o vídeo ao celular."; }
    public String diagnostic() { return lastRequest; }

    private final boolean compatibleMp4;
    private final java.util.Map<String, java.io.File> preparedFiles = new java.util.concurrent.ConcurrentHashMap<>();
    private HlsStream activeHls;
    private int nextHlsSegment;
    private static final int HLS_FIRST_CHUNK_MS = 30000;
    private static final int HLS_NEXT_CHUNK_MS = 180000;
    private static final int HLS_MAX_CHUNK_SEGMENTS = 60;
    public StreamProxy(Context context) { this(context, true); }
    StreamProxy(Context context, boolean compatibleMp4) { app = context.getApplicationContext(); this.compatibleMp4 = compatibleMp4; }
    public interface ProgressListener { void update(String message); }
    private volatile ProgressListener progress;
    public void setProgressListener(ProgressListener listener) { progress = listener; }


    public synchronized void start(String pageUrl, String agent) throws IOException {
        referer = pageUrl == null ? "" : pageUrl;
        userAgent = agent == null ? "VieraFlow/0.1" : agent;
        if (listener != null) return;
        String address = wifiAddress();
        if (address == null) throw new IOException("Conecte celular e TV na mesma rede Wi-Fi.");
        // Bind the advertised Wi-Fi IPv4 address explicitly. A wildcard socket can
        // bind IPv6 only on some Android devices while the Viera requests IPv4.
        listener = new ServerSocket(0, 50, java.net.InetAddress.getByName(address));
        listener.setReuseAddress(true);
        baseUrl = "http://" + address + ":" + listener.getLocalPort() + "/media/" + token + "/";
        threads.execute(() -> {
            while (!listener.isClosed()) {
                try {
                    Socket client = listener.accept();
                    threads.execute(() -> serve(client));
                } catch (Exception e) {
                    if (!listener.isClosed()) android.util.Log.w("VieraFlow", "Proxy accept", e);
                    break;
                }
            }
        });
    }

    public String proxyUrl(String remote) {
        if (baseUrl == null) throw new IllegalStateException("Proxy não foi iniciado");
        String extension = ".bin";
        try {
            String path = new URL(remote).getPath().toLowerCase(java.util.Locale.ROOT);
            for (String supported : new String[]{".mp4", ".m3u8", ".ts", ".m4s", ".aac", ".vtt", ".key", ".webm", ".mov"}) {
                if (path.endsWith(supported)) { extension = supported; break; }
            }
        } catch (Exception ignored) { }
        return baseUrl + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(remote.getBytes(StandardCharsets.UTF_8)) + extension;
    }

    private String wifiAddress() {
        WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null || wifi.getConnectionInfo() == null) return null;
        int ip = wifi.getConnectionInfo().getIpAddress();
        if (ip == 0) return null;
        return (ip & 255) + "." + ((ip >>> 8) & 255) + "." + ((ip >>> 16) & 255) + "." + ((ip >>> 24) & 255);
    }

    private void serve(Socket client) {
        boolean continuousHlsRequest = false;
        try (Socket socket = client) {
            socket.setSoTimeout(30000);
            InputStream request = new BufferedInputStream(socket.getInputStream());
            OutputStream response = new BufferedOutputStream(socket.getOutputStream());
            String first = line(request);
            String[] parts = first.split(" ");
            if (parts.length < 2 || (!"GET".equals(parts[0]) && !"HEAD".equals(parts[0]))) {
                sendError(response, 405, "Método inválido"); return;
            }
            lastRequest = "A TV solicitou " + parts[0] + " ao celular.";
            String range = null;
            String requestLine;
            while (!(requestLine = line(request)).isEmpty()) {
                if (requestLine.length() > 8192) throw new IOException("Header longo");
                if (requestLine.toLowerCase(java.util.Locale.ROOT).startsWith("range:")) {
                    range = requestLine.substring(6).trim();
                    if (!range.matches("bytes=[0-9]*-[0-9]*(,[0-9]*-[0-9]*)*")) range = null;
                }
            }
            String filePrefix = "/file/" + token + "/";
            if (parts[1].startsWith(filePrefix)) {
                serveFile(response, parts[1].substring(filePrefix.length()), range, "HEAD".equals(parts[0]));
                response.flush(); return;
            }
            String tsPrefix = "/ts/" + token + "/";
            if (parts[1].startsWith(tsPrefix)) {
                continuousHlsRequest = "GET".equals(parts[0]);
                streamHlsAsTs(response, parts[1].substring(tsPrefix.length()), "HEAD".equals(parts[0]));
                response.flush();
                return;
            }
            String prefix = "/media/" + token + "/";
            if (!parts[1].startsWith(prefix)) { sendError(response, 403, "Não autorizado"); return; }
            String original;
            try {
                String encoded = parts[1].substring(prefix.length());
                int extension = encoded.lastIndexOf('.');
                if (extension > 0) encoded = encoded.substring(0, extension);
                original = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            }
            catch (Exception e) { sendError(response, 400, "URI inválida"); return; }
            URL url = new URL(original);
            if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) {
                sendError(response, 400, "Protocolo inválido"); return;
            }
            fetch(response, url, range, "HEAD".equals(parts[0]));
            response.flush();
        } catch (Exception e) {
            lastRequest = "Falha no servidor do celular: " + e.getMessage();
            android.util.Log.w("VieraFlow", "Proxy stream", e);
            // For an active TS GET, even a broken pipe matters: the receiver may
            // have discarded the stream because of a decoder/protocol problem.
            if (errorListener != null && (continuousHlsRequest || !(e instanceof java.net.SocketException)))
                errorListener.failed((continuousHlsRequest && e instanceof java.net.SocketException
                        ? "A TV fechou o fluxo MPEG-TS. " : "")
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                        + (continuousHlsRequest ? " | " + lastSegmentDiagnostic : ""));
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (bytes.size() > 8192) throw new IOException("Request longo");
            if (c != '\r') bytes.write(c);
        }
        return bytes.toString("ISO-8859-1");
    }

    private void fetch(OutputStream out, URL url, String range, boolean head) throws IOException {
        HttpURLConnection conn = open(url);
        // Some video CDNs allow GET but reject HEAD. The TV probes with HEAD,
        // so use a one-byte GET to obtain the same metadata without downloading the movie.
        if (head) conn.setRequestProperty("Range", "bytes=0-0");
        else if (range != null) conn.setRequestProperty("Range", range);
        try {
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) {
                lastRequest = "A fonte recusou o pedido da TV: HTTP " + code + ".";
                sendError(out, 502, "Fonte respondeu HTTP " + code); return;
            }
            String contentType = conn.getContentType();
            String advertised = mediaTypes.get(url.toString());
            if (advertised != null) contentType = advertised;
            boolean playlist = (contentType != null && (contentType.contains("mpegurl") || contentType.contains("m3u8")))
                    || conn.getURL().getPath().toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8");
            if (playlist) {
                try (InputStream in = conn.getInputStream()) {
                    ByteArrayOutputStream src = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (src.size() + n > 4 * 1024 * 1024) throw new IOException("Playlist acima de 4 MB");
                        src.write(buffer, 0, n);
                    }
                    byte[] transformed = rewritePlaylist(src.toString("UTF-8"), conn.getURL()).getBytes(StandardCharsets.UTF_8);
                    sendHeaders(out, 200, "application/vnd.apple.mpegurl", transformed.length, null, null);
                    if (!head) out.write(transformed);
                }
                return;
            }
            long length = conn.getContentLengthLong();
            if (head && code == 206) {
                String contentRange = conn.getHeaderField("Content-Range");
                if (contentRange != null) {
                    Matcher total = Pattern.compile("bytes\\s+\\d+-\\d+/(\\d+)", Pattern.CASE_INSENSITIVE).matcher(contentRange);
                    if (total.matches()) length = Long.parseLong(total.group(1));
                }
            }
            lastRequest = "A fonte respondeu ao pedido da TV: HTTP " + code + ".";
            sendHeaders(out, head ? 200 : code, contentType == null ? "application/octet-stream" : contentType,
                    length, head ? null : conn.getHeaderField("Content-Range"), head ? "bytes" : conn.getHeaderField("Accept-Ranges"));
            if (!head) try (InputStream in = conn.getInputStream()) {
                byte[] bytes = new byte[65536]; int n;
                while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
            }
        } finally { conn.disconnect(); }
    }

    static final String DLNA_TS = "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";
    private final java.util.Map<String, HlsStream> hlsStreams = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String lastSegmentDiagnostic = "Sem segmento entregue à TV.";
    public String streamDiagnostic() { return lastSegmentDiagnostic; }
    private final java.util.Map<String, String> mediaTypes = new java.util.concurrent.ConcurrentHashMap<>();
    public interface ErrorListener { void failed(String error); }
    private volatile ErrorListener errorListener;
    public void setErrorListener(ErrorListener listener) { errorListener = listener; }
    public static final class Media {
        public final String url, mime;
        /** Playing time of a prepared HLS chunk and how long preparing it took (0 when unknown). */
        public final long durationMs, prepareMs;
        Media(String url, String mime) { this(url, mime, 0, 0); }
        Media(String url, String mime, long durationMs, long prepareMs) {
            this.url = url; this.mime = mime; this.durationMs = durationMs; this.prepareMs = prepareMs;
        }
    }

    public static final class SourceInfo {
        public final long bytes, durationMs;
        public final boolean hls;
        SourceInfo(long bytes, long durationMs, boolean hls) {
            this.bytes = bytes; this.durationMs = durationMs; this.hls = hls;
        }
    }

    /** Read MP4 box headers by Range, so choosing a movie never downloads it. */
    public SourceInfo inspect(String remote) throws IOException {
        URL url = new URL(remote);
        if (url.getPath().toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8"))
            return new SourceInfo(-1, -1, true);
        HttpURLConnection conn = open(url);
        conn.setRequestProperty("Range", "bytes=0-15");
        long total; byte[] prefix;
        try {
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) throw new IOException("HTTP " + code);
            String type = conn.getContentType();
            if (type != null && type.toLowerCase(java.util.Locale.ROOT).contains("mpegurl"))
                return new SourceInfo(-1, -1, true);
            total = totalLength(conn, code);
            prefix = readAtMost(conn.getInputStream(), 16);
        } finally { conn.disconnect(); }
        if (prefix.length >= 8 && new String(prefix, 4, 4, StandardCharsets.US_ASCII).equals("ftyp") && total > 0)
            return new SourceInfo(total, mp4Duration(url, total), false);
        return new SourceInfo(total, -1, false);
    }

    private static long totalLength(HttpURLConnection conn, int code) {
        if (code == 206) {
            String range = conn.getHeaderField("Content-Range");
            if (range != null) {
                Matcher m = Pattern.compile("bytes\\s+\\d+-\\d+/(\\d+)", Pattern.CASE_INSENSITIVE).matcher(range);
                if (m.matches()) try { return Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) { }
            }
        }
        return code == 200 ? conn.getContentLengthLong() : -1;
    }

    private static byte[] readAtMost(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 65536));
        byte[] buf = new byte[8192]; int n;
        while (out.size() < limit && (n = in.read(buf, 0, Math.min(buf.length, limit - out.size()))) > 0)
            out.write(buf, 0, n);
        return out.toByteArray();
    }

    private byte[] rangeBytes(URL url, long offset, int length) throws IOException {
        HttpURLConnection conn = open(url);
        conn.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + length - 1));
        try {
            int code = conn.getResponseCode();
            if (code != 206 && !(code == 200 && offset == 0)) return new byte[0];
            return readAtMost(conn.getInputStream(), length);
        } finally { conn.disconnect(); }
    }

    private static long u32(byte[] data, int pos) {
        return ((long)(data[pos] & 255) << 24) | ((long)(data[pos + 1] & 255) << 16)
                | ((long)(data[pos + 2] & 255) << 8) | (data[pos + 3] & 255);
    }
    private static long u64(byte[] data, int pos) {
        long hi = u32(data, pos), lo = u32(data, pos + 4);
        return hi > 0x7fffffffL ? -1 : (hi << 32) | lo;
    }

    private long mp4Duration(URL url, long total) {
        try {
            long offset = 0;
            for (int boxes = 0; boxes < 32 && offset + 8 <= total; boxes++) {
                byte[] header = rangeBytes(url, offset, 16);
                if (header.length < 8) return -1;
                long size = u32(header, 0);
                int headerSize = 8;
                if (size == 1) { if (header.length < 16) return -1; size = u64(header, 8); headerSize = 16; }
                else if (size == 0) size = total - offset;
                if (size < headerSize || size > total - offset) return -1;
                String type = new String(header, 4, 4, StandardCharsets.US_ASCII);
                if ("moov".equals(type)) {
                    byte[] moov = rangeBytes(url, offset, (int)Math.min(size, 131072));
                    for (int pos = headerSize; pos + 8 <= moov.length;) {
                        long childSize = u32(moov, pos);
                        if (childSize < 8 || childSize > moov.length - pos) return -1;
                        if (moov[pos + 4] == 'm' && moov[pos + 5] == 'v'
                                && moov[pos + 6] == 'h' && moov[pos + 7] == 'd') {
                            int version = moov[pos + 8] & 255;
                            int scaleAt = pos + (version == 1 ? 28 : 20);
                            int durationAt = pos + (version == 1 ? 32 : 24);
                            if (durationAt + (version == 1 ? 8 : 4) > moov.length) return -1;
                            long scale = u32(moov, scaleAt);
                            long duration = version == 1 ? u64(moov, durationAt) : u32(moov, durationAt);
                            return scale <= 0 || duration < 0 ? -1 : (long)(duration * 1000.0 / scale);
                        }
                        pos += (int)childSize;
                    }
                    return -1;
                }
                offset += size;
            }
        } catch (Exception ignored) { }
        return -1;
    }

    /** Executed on a worker: check the source and first TS segment before opening the TV player. */
    public Media prepare(String remote) throws Exception {
        URL url = new URL(remote);
        HttpURLConnection conn = open(url);
        boolean hls; String type;
        try {
            conn.setRequestProperty("Range", "bytes=0-4095");
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) throw new IOException("Fonte respondeu HTTP " + code);
            type = conn.getContentType();
            if (type != null) type = type.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
            byte[] probe = new byte[4096]; int length = 0;
            try (InputStream in = conn.getInputStream()) {
                while (length < probe.length) {
                    int count = in.read(probe, length, probe.length - length);
                    if (count < 0) break;
                    length += count;
                }
            }
            String prefix = new String(probe, 0, length, StandardCharsets.UTF_8).trim();
            hls = prefix.startsWith("#EXTM3U") || prefix.startsWith("\uFEFF#EXTM3U")
                    || (type != null && (type.contains("mpegurl") || type.contains("m3u8")))
                    || conn.getURL().getPath().toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8");
            if (!hls) {
                if (length >= 8 && new String(probe, 4, 4, StandardCharsets.US_ASCII).equals("ftyp")) type = "video/mp4";
                else if (HlsStream.syncOffset(java.util.Arrays.copyOf(probe, length)) >= 0) type = "video/mpeg";
                else if (type == null || !type.startsWith("video/"))
                    throw new IOException("O link retornou uma página ou formato desconhecido, não um vídeo. Abra o player e detecte novamente.");
            }
        } finally { conn.disconnect(); }
        if (hls) {
            HlsStream stream = new HlsStream(this::downloadResource, url);
            if (compatibleMp4) {
                if (!stream.isComplete()) throw new IOException("Esta fonte é ao vivo. Escolha um vídeo sob demanda.");
                activeHls = stream;
                nextHlsSegment = 0;
                Media firstChunk = prepareNextHls();
                if (firstChunk == null) throw new IOException("Playlist HLS vazia.");
                discardExcept(firstChunk.url);
                return firstChunk;
            }
            String id = UUID.randomUUID().toString();
            hlsStreams.clear(); hlsStreams.put(id, stream);
            lastSegmentDiagnostic = "Playlist HLS: " + stream.segmentCount() + " segmentos, "
                    + HlsStream.formatTime(stream.totalDurationMs())
                    + (stream.isComplete() ? " de duração declarada." : " disponíveis inicialmente (playlist dinâmica).");
            android.util.Log.i("VieraFlow", lastSegmentDiagnostic);
            return new Media(baseUrl.replace("/media/", "/ts/") + id + ".ts", "video/mpeg");
        }
        activeHls = null;
        discardExcept("");
        mediaTypes.put(remote, type);
        return new Media(proxyUrl(remote), type);
    }

    public synchronized boolean hasMoreHls() {
        return activeHls != null && nextHlsSegment < activeHls.segmentCount();
    }

    public synchronized Media prepareNextHls() throws Exception {
        if (!hasMoreHls()) return null;
        int start = nextHlsSegment;
        int count = activeHls.segmentsForDuration(start,
                start == 0 ? HLS_FIRST_CHUNK_MS : HLS_NEXT_CHUNK_MS, HLS_MAX_CHUNK_SEGMENTS);
        long began = System.nanoTime();
        Media prepared = prepareMp4Chunk(activeHls, start, count);
        long durationMs = activeHls.durationMs(start, count);
        nextHlsSegment += count;
        return new Media(prepared.url, prepared.mime, durationMs, (System.nanoTime() - began) / 1000000L);
    }

    /** Playing time of the chunk prepareNextHls() would build next (0 when none is left). */
    public synchronized long nextHlsDurationMs() {
        if (!hasMoreHls()) return 0;
        int start = nextHlsSegment;
        int count = activeHls.segmentsForDuration(start,
                start == 0 ? HLS_FIRST_CHUNK_MS : HLS_NEXT_CHUNK_MS, HLS_MAX_CHUNK_SEGMENTS);
        return activeHls.durationMs(start, count);
    }

    public synchronized void discardExcept(String mediaUrl) {
        String keep = mediaUrl.substring(mediaUrl.lastIndexOf('/') + 1);
        for (java.util.Map.Entry<String, java.io.File> entry : preparedFiles.entrySet()) {
            if (!entry.getKey().equals(keep)) {
                java.io.File removed = preparedFiles.remove(entry.getKey());
                if (removed != null) removed.delete();
            }
        }
    }

    private Media prepareMp4Chunk(HlsStream stream, int start, int count) throws Exception {
        java.io.File ts = java.io.File.createTempFile("vieraflow-", ".ts", app.getCacheDir());
        java.io.File mp4 = java.io.File.createTempFile("vieraflow-", ".mp4", app.getCacheDir());
        boolean success = false;
        try {
            if (progress != null) progress.update("Preparando um trecho de " + count + " partes para a TV…");
            try (java.io.FileOutputStream file = new java.io.FileOutputStream(ts)) {
                stream.writeSegments(file, start, count);
            }
            if (progress != null) progress.update("Finalizando trecho MP4…");
            Mp4Remuxer.convert(ts, mp4);
            String id = UUID.randomUUID().toString() + ".mp4";
            preparedFiles.put(id, mp4); success = true;
            return new Media(baseUrl.replace("/media/", "/file/") + id, "video/mp4");
        } finally { ts.delete(); if (!success) mp4.delete(); }
    }

    private void serveFile(OutputStream out, String id, String range, boolean head) throws IOException {
        java.io.File file = preparedFiles.get(id);
        if (file == null || !file.isFile()) { lastRequest = "O trecho preparado expirou antes da TV abrir."; sendError(out, 404, "Vídeo expirado. Envie novamente."); return; }
        lastRequest = "A TV acessou o MP4 preparado no celular.";
        long length = file.length(), start = 0, end = length - 1;
        if (range != null) {
            if (range.contains(",")) { sendError(out, 416, "Apenas um intervalo por pedido."); return; }
            String[] span = range.substring(6).split("-", -1);
            try {
                if (span[0].isEmpty()) start = Math.max(0, length - Long.parseLong(span[1]));
                else { start = Long.parseLong(span[0]); if (!span[1].isEmpty()) end = Math.min(end, Long.parseLong(span[1])); }
            } catch (NumberFormatException e) { sendError(out, 416, "Intervalo inválido."); return; }
            if (start > end || start >= length) { sendError(out, 416, "Intervalo fora do arquivo."); return; }
        }
        long remaining = end - start + 1;
        sendHeaders(out, range == null ? 200 : 206, "video/mp4", remaining,
                range == null ? null : "bytes " + start + "-" + end + "/" + length, "bytes");
        if (head) return;
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(file, "r")) {
            input.seek(start); byte[] buffer = new byte[65536];
            while (remaining > 0) {
                int n = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                if (n < 0) throw new IOException("Arquivo preparado incompleto.");
                out.write(buffer, 0, n); remaining -= n;
            }
        }
    }

    private HttpURLConnection open(URL url) throws IOException {
        if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol()))
            throw new IOException("Use uma fonte HTTP ou HTTPS.");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(12000); conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", userAgent);
        conn.setRequestProperty("Accept-Encoding", "identity");
        if (!referer.isEmpty()) conn.setRequestProperty("Referer", referer);
        String cookie = CookieManager.getInstance().getCookie(url.toString());
        if (cookie != null) conn.setRequestProperty("Cookie", cookie);
        return conn;
    }

    private HlsStream.Resource downloadResource(URL url, String range, int limit) throws IOException {
        HttpURLConnection conn = open(url);
        if (range != null) conn.setRequestProperty("Range", range);
        long started = System.nanoTime();
        try {
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) throw new IOException("Fonte respondeu HTTP " + code);
            long skip = 0, length = -1;
            if (range != null) {
                String[] span = range.substring(6).split("-");
                long start = Long.parseLong(span[0]);
                length = Long.parseLong(span[1]) - start + 1;
                if (length > limit) throw new IOException("Segmento acima do limite de memória.");
                if (code == 200) skip = start;
                else {
                    String actual = conn.getHeaderField("Content-Range");
                    if (actual == null || !actual.startsWith("bytes " + start + "-"))
                        throw new IOException("A fonte retornou um intervalo diferente do solicitado.");
                }
            } else if (code == 206) throw new IOException("A fonte retornou um arquivo incompleto.");
            try (InputStream in = conn.getInputStream()) {
                while (skip > 0) {
                    long count = in.skip(skip);
                    if (count == 0) { if (in.read() == -1) throw new IOException("Intervalo fora do arquivo."); count = 1; }
                    skip -= count;
                }
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[65536]; int n;
                while (length < 0 || buf.size() < length) {
                    n = in.read(chunk, 0, length < 0 ? chunk.length : (int)Math.min(chunk.length, length - buf.size()));
                    if (n == -1) break;
                    if (buf.size() + n > limit) throw new IOException("Arquivo acima do limite de memória.");
                    buf.write(chunk, 0, n);
                }
                if (length >= 0 && buf.size() != length) throw new IOException("Segmento recebido incompleto.");
                long elapsedMs = (System.nanoTime() - started) / 1000000L;
                android.util.Log.i("VieraFlow", "HLS HTTP " + code + " host=" + url.getHost()
                        + " bytes=" + buf.size() + " ms=" + elapsedMs);
                return new HlsStream.Resource(conn.getURL(), buf.toByteArray());
            }
        } catch (IOException error) {
            android.util.Log.w("VieraFlow", "HLS failed host=" + url.getHost()
                    + " ms=" + ((System.nanoTime() - started) / 1000000L), error);
            throw error;
        } finally { conn.disconnect(); }
    }

    private void streamHlsAsTs(OutputStream out, String encoded, boolean head) throws Exception {
        String id = encoded.endsWith(".ts") ? encoded.substring(0, encoded.length() - 3) : encoded;
        HlsStream stream = hlsStreams.get(id);
        if (stream == null) { sendError(out, 404, "Transmissão expirada. Envie o vídeo novamente."); return; }
        String headers = "HTTP/1.1 200 OK\r\nContent-Type: video/mpeg\r\n"
                + "transferMode.dlna.org: Streaming\r\ncontentFeatures.dlna.org: " + DLNA_TS + "\r\n"
                + "Accept-Ranges: none\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.ISO_8859_1)); out.flush();
        if (!head) {
            lastSegmentDiagnostic = "A TV iniciou GET do fluxo MPEG-TS. " + stream.segmentCount() + " segmentos listados.";
            final int[] lastShown = { 0 };
            stream.setProgressListener((ordinal, total, elapsedMs) -> {
                lastSegmentDiagnostic = "Segmento " + ordinal + "/" + total
                        + " enviado ao socket; posição de mídia aprox. " + HlsStream.formatTime(elapsedMs);
                if (ordinal == 1 || ordinal == total || ordinal - lastShown[0] >= 5) {
                    lastShown[0] = ordinal;
                    android.util.Log.i("VieraFlow", lastSegmentDiagnostic);
                }
            });
            stream.write(out);
            lastRequest = "O proxy entregou todos os segmentos indicados na playlist (" 
                    + HlsStream.formatTime(stream.totalDurationMs()) + ").";
            android.util.Log.i("VieraFlow", lastRequest);
        }
    }

    private String rewritePlaylist(String value, URL playlistUrl) {
        StringBuilder rewritten = new StringBuilder();
        Pattern uri = Pattern.compile("URI=\"([^\"]+)\"");
        for (String row : value.split("\r?\n", -1)) {
            if (row.startsWith("#")) {
                Matcher m = uri.matcher(row);
                StringBuffer updated = new StringBuffer();
                while (m.find()) {
                    try { m.appendReplacement(updated, Matcher.quoteReplacement("URI=\"" + proxyUrl(new URL(playlistUrl, m.group(1)).toString()) + "\"")); }
                    catch (Exception ignored) { /* Leave malformed playlist entry alone. */ }
                }
                m.appendTail(updated);
                rewritten.append(updated);
            } else if (row.trim().isEmpty()) {
                rewritten.append(row);
            } else {
                try { rewritten.append(proxyUrl(new URL(playlistUrl, row.trim()).toString())); }
                catch (Exception e) { rewritten.append(row); }
            }
            rewritten.append('\n');
        }
        return rewritten.toString();
    }

    private static void sendHeaders(OutputStream out, int code, String type, long length, String contentRange, String acceptRanges) throws IOException {
        String status = code == 206 ? "Partial Content" : "OK";
        StringBuilder b = new StringBuilder("HTTP/1.1 " + code + " " + status + "\r\n");
        b.append("Content-Type: ").append(type.replace("\r", "").replace("\n", "")).append("\r\n");
        if (length >= 0) b.append("Content-Length: ").append(length).append("\r\n");
        if (contentRange != null) b.append("Content-Range: ").append(contentRange).append("\r\n");
        b.append("Accept-Ranges: ").append(acceptRanges == null ? "none" : acceptRanges).append("\r\n");
        b.append("Connection: close\r\n\r\n");
        out.write(b.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void sendError(OutputStream out, int code, String message) throws IOException {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " Error\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
        out.flush();
    }

    public synchronized void close() {
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        hlsStreams.clear();
        for (java.io.File file : preparedFiles.values()) file.delete();
        preparedFiles.clear();
        threads.shutdownNow();
    }
}
