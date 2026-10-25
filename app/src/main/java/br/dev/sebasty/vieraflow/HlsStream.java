package br.dev.sebasty.vieraflow;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** HLS MPEG-TS relay. Does not transcode or claim support for separate audio/fMP4. */
final class HlsStream {
    interface Fetcher { Resource fetch(URL url, String range, int limit) throws IOException; }
    static final class Resource {
        final URL url; final byte[] data;
        Resource(URL url, byte[] data) { this.url = url; this.data = data; }
    }
    static final class Segment {
        final URL url; final String range; final byte[] key, iv; final long sequence; final int durationMs;
        Segment(URL url, String range, byte[] key, byte[] iv, long sequence, int durationMs) {
            this.url = url; this.range = range; this.key = key; this.iv = iv; this.sequence = sequence; this.durationMs = durationMs;
        }
    }
    static final class Playlist {
        final URL url; final List<Segment> segments; final boolean ended; final int intervalMs;
        Playlist(URL url, List<Segment> segments, boolean ended, int intervalMs) {
            this.url = url; this.segments = segments; this.ended = ended; this.intervalMs = intervalMs;
        }
    }
    /** Highest variant height preferred for the TV. Lower it (e.g. 480) if the Wi-Fi link is the bottleneck. */
    static final int MAX_VARIANT_HEIGHT = 720;
    interface ProgressListener { void segment(int ordinal, int total, long movieMs); }
    private volatile ProgressListener progressListener;
    void setProgressListener(ProgressListener listener) { this.progressListener = listener; }
    private final Fetcher fetcher;
    final Playlist initial;
    private final byte[] first;
    /** Small bootstrap buffer before offering the TS stream to a DLNA renderer. */
    private final List<byte[]> bootstrap = new ArrayList<>();
    private static final int BOOTSTRAP_SEGMENTS = 3;
    private static final int QUEUE_SEGMENTS = 10;

    HlsStream(Fetcher fetcher, URL url) throws Exception {
        this.fetcher = fetcher;
        initial = load(url, 0);
        first = segment(initial.segments.get(0)); // Fail before SetAVTransportURI/HTTP 200.
        validateVideo(first);
        bootstrap.add(first);
        // For VOD, cache the next segments before starting the TV. Otherwise the
        // renderer may be starved on the first DNS/TLS lookup after opening GET.
        if (initial.ended) {
            for (int i = 1; i < Math.min(BOOTSTRAP_SEGMENTS, initial.segments.size()); i++)
                bootstrap.add(segment(initial.segments.get(i)));
        }
    }

    private static String attr(String row, String name) {
        Matcher m = Pattern.compile("(?:^|[:,])\\s*" + name + "=(\"[^\"]*\"|[^,]*)").matcher(row);
        if (!m.find()) return null;
        String v = m.group(1).trim();
        return v.startsWith("\"") && v.endsWith("\"") ? v.substring(1, v.length() - 1) : v;
    }

    private Playlist load(URL url, int depth) throws Exception {
        if (depth > 4) throw new IOException("Playlist HLS com níveis demais.");
        Resource resource = fetcher.fetch(url, null, 4 * 1024 * 1024);
        URL base = resource.url; // Relative links are relative to the FINAL redirect URL.
        String text = new String(resource.data, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (!text.startsWith("#EXTM3U")) throw new IOException("O endereço não retornou uma playlist HLS.");
        String[] rows = text.split("\r?\n");
        if (text.contains("#EXT-X-STREAM-INF:")) {
            Map<String, Boolean> externalAudio = new HashMap<>();
            for (String raw : rows) {
                String r = raw.trim();
                if (r.startsWith("#EXT-X-MEDIA:") && "AUDIO".equals(attr(r, "TYPE"))) {
                    String group = attr(r, "GROUP-ID");
                    boolean separate = attr(r, "URI") != null;
                    externalAudio.put(group, separate || Boolean.TRUE.equals(externalAudio.get(group)));
                }
            }
            URL best = null, fallback = null; long bestBw = -1, fallbackBw = Long.MAX_VALUE;
            for (int i = 0; i < rows.length; i++) {
                String r = rows[i].trim();
                if (!r.startsWith("#EXT-X-STREAM-INF:")) continue;
                int j = i + 1;
                while (j < rows.length && (rows[j].trim().isEmpty() || rows[j].trim().startsWith("#"))) j++;
                if (j >= rows.length) throw new IOException("Variante HLS sem endereço.");
                String codecs = attr(r, "CODECS");
                if (codecs != null && !compatibleCodecs(codecs)) continue;
                if (Boolean.TRUE.equals(externalAudio.get(attr(r, "AUDIO")))) continue;
                long bw = number(attr(r, "BANDWIDTH"), 0);
                String res = attr(r, "RESOLUTION");
                long height = res == null ? 0 : number(res.substring(res.indexOf('x') + 1), 0);
                URL candidate = new URL(base, rows[j].trim());
                if ((height == 0 || height <= MAX_VARIANT_HEIGHT) && bw > bestBw) { bestBw = bw; best = candidate; }
                if ((height == 0 || height <= 1080) && bw < fallbackBw) { fallbackBw = bw; fallback = candidate; }
            }
            URL chosen = best != null ? best : fallback;
            if (chosen == null) throw new IOException("Este HLS usa áudio separado ou codecs incompatíveis. Escolha outra fonte com H.264 e áudio no mesmo vídeo.");
            return load(chosen, depth + 1);
        }
        List<Segment> segments = new ArrayList<>();
        Map<String, byte[]> keys = new HashMap<>();
        long seq = 0, nextOffset = -1; String previousRangeUrl = null, pendingRange = null;
        byte[] key = null, explicitIv = null; boolean ended = false; int interval = 2000, pendingDurationMs = 2000;
        for (String raw : rows) {
            String r = raw.trim();
            if (r.isEmpty()) continue;
            if (r.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                seq = Long.parseLong(r.substring(r.indexOf(':') + 1).trim());
                if (seq < 0) throw new IOException("Sequência HLS inválida.");
            } else if (r.startsWith("#EXT-X-TARGETDURATION:")) {
                long seconds = number(r.substring(r.indexOf(':') + 1), 4);
                interval = (int) Math.max(500, Math.min(10000, seconds * 500));
            } else if (r.equals("#EXT-X-ENDLIST")) ended = true;
            else if (r.startsWith("#EXTINF:")) {
                try { pendingDurationMs = Math.max(100, (int)(Double.parseDouble(r.substring(8).split(",", 2)[0]) * 1000)); }
                catch (NumberFormatException ignored) { pendingDurationMs = 2000; }
            }
            else if (r.startsWith("#EXT-X-MAP:")) throw new IOException("Esta fonte usa HLS fMP4. Escolha outra fonte em MPEG-TS ou MP4; este app não recodifica vídeos.");
            else if (r.equals("#EXT-X-DISCONTINUITY") || r.equals("#EXT-X-GAP"))
                throw new IOException("Este HLS contém cortes ou mudanças de formato que precisam de remultiplexação. Escolha outra fonte.");
            else if (r.startsWith("#EXT-X-BYTERANGE:")) pendingRange = r.substring(r.indexOf(':') + 1).trim();
            else if (r.startsWith("#EXT-X-KEY:")) {
                String method = attr(r, "METHOD");
                if ("NONE".equals(method)) { key = null; explicitIv = null; }
                else if ("AES-128".equals(method)) {
                    String format = attr(r, "KEYFORMAT"), uri = attr(r, "URI");
                    if (format != null && !"identity".equals(format)) throw new IOException("Proteção HLS não suportada.");
                    if (uri == null) throw new IOException("Chave HLS sem endereço.");
                    URL keyUrl = new URL(base, uri);
                    key = keys.get(keyUrl.toString());
                    if (key == null) {
                        key = fetcher.fetch(keyUrl, null, 16).data;
                        if (key.length != 16) throw new IOException("Chave AES-128 inválida.");
                        keys.put(keyUrl.toString(), key);
                    }
                    String hex = attr(r, "IV");
                    explicitIv = hex == null ? null : iv(hex);
                } else throw new IOException("Criptografia HLS não suportada: " + method);
            } else if (!r.startsWith("#")) {
                URL segmentUrl = new URL(base, r); String range = null;
                if (pendingRange != null) {
                    String[] fields = pendingRange.split("@", -1);
                    long length = Long.parseLong(fields[0]);
                    if (fields.length > 2 || length <= 0) throw new IOException("Intervalo HLS inválido.");
                    long offset = fields.length == 2 ? Long.parseLong(fields[1]) : nextOffset;
                    if (offset < 0 || (fields.length == 1 && !segmentUrl.toString().equals(previousRangeUrl)))
                        throw new IOException("Intervalo HLS sem posição inicial.");
                    nextOffset = Math.addExact(offset, length); previousRangeUrl = segmentUrl.toString();
                    range = "bytes=" + offset + "-" + (nextOffset - 1); pendingRange = null;
                } else { nextOffset = -1; previousRangeUrl = null; }
                byte[] segmentIv = key == null ? null : explicitIv != null ? explicitIv : sequenceIv(seq);
                segments.add(new Segment(segmentUrl, range, key, segmentIv, seq++, pendingDurationMs));
                pendingDurationMs = 2000;
            }
        }
        if (pendingRange != null || segments.isEmpty()) throw new IOException("Playlist HLS incompleta ou sem segmentos.");
        return new Playlist(base, segments, ended, interval);
    }

    private static long number(String value, long fallback) {
        try { return Long.parseLong(value); } catch (Exception e) { return fallback; }
    }
    private static boolean compatibleCodecs(String value) {
        boolean video = false;
        for (String raw : value.toLowerCase(Locale.ROOT).split(",")) {
            String c = raw.trim();
            if (c.startsWith("avc1") || c.startsWith("avc3")) video = true;
            else if (!(c.startsWith("mp4a.40.2") || c.startsWith("mp4a.40.5") || c.startsWith("mp4a.40.29")
                    || c.equals("ac-3") || c.equals("mp4a.6b") || c.equals("mp4a.69"))) return false;
        }
        return video;
    }
    private static byte[] iv(String hex) throws IOException {
        if (!hex.matches("0[xX][0-9a-fA-F]{1,32}")) throw new IOException("IV AES inválido.");
        String h = hex.substring(2);
        while (h.length() < 32) h = "0" + h;
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
    private static byte[] sequenceIv(long seq) {
        byte[] out = new byte[16];
        for (int i = 15; i >= 8; i--) { out[i] = (byte) seq; seq >>>= 8; }
        return out;
    }
    private byte[] segment(Segment segment) throws Exception {
        byte[] data = fetchSegmentWithRetry(segment);
        if (segment.key != null) {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(segment.key, "AES"), new IvParameterSpec(segment.iv));
            data = cipher.doFinal(data);
        }
        int offset = syncOffset(data);
        if (offset < 0) throw new IOException("A fonte não contém MPEG-TS válido. Não foi enviada à TV.");
        int size = data.length - offset;
        if (size % 188 != 0) throw new IOException("Segmento MPEG-TS truncado.");
        for (int i = offset; i < data.length; i += 188)
            if (data[i] != 0x47) throw new IOException("Pacote MPEG-TS inválido.");
        return offset == 0 ? data : Arrays.copyOfRange(data, offset, data.length);
    }
    /** Retry only transient failures. Repeating a 403/404 cannot refresh a signed URL. */
    private static boolean transientFailure(IOException e) {
        if (e instanceof UnknownHostException || e instanceof SocketTimeoutException) return true;
        String msg = e.getMessage();
        return msg != null && (msg.contains("HTTP 429") || msg.contains("HTTP 500")
                || msg.contains("HTTP 502") || msg.contains("HTTP 503")
                || msg.contains("HTTP 504") || msg.contains("Connection reset"));
    }

    private byte[] fetchSegmentWithRetry(Segment segment) throws Exception {
        long pauseMs = 300;
        for (int attempt = 1; attempt <= 5; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Transmissão cancelada.");
            try {
                return fetcher.fetch(segment.url, segment.range, 32 * 1024 * 1024).data;
            } catch (IOException error) {
                if (attempt == 5 || !transientFailure(error)) throw error;
                // Never log a complete signed URL or cookie. Only host and attempt count.
                System.err.println("VieraFlow: HLS transient failure on " + segment.url.getHost()
                        + " attempt " + attempt + "/5: " + error.getClass().getSimpleName());
                try { Thread.sleep(pauseMs); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Transmissão cancelada.", e); }
                pauseMs = Math.min(3000, pauseMs * 2);
            }
        }
        throw new IOException("Falha ao baixar o segmento HLS.");
    }

    static int syncOffset(byte[] data) {
        int limit = Math.min(data.length - 376, 65536);
        for (int i = 0; i < limit; i++)
            if (data[i] == 0x47 && data[i + 188] == 0x47 && data[i + 376] == 0x47) return i;
        return -1;
    }
    private static void validateVideo(byte[] data) throws IOException {
        // Inspect complete PMT sections contained in a packet; do not guess from file extension.
        for (int i = 0; i + 188 <= data.length; i += 188) {
            if ((data[i + 1] & 0x40) == 0) continue;
            int control = (data[i + 3] >>> 4) & 3;
            if (control == 0 || control == 2) continue;
            int p = i + 4;
            if (control == 3) p += 1 + (data[p] & 255);
            if (p >= i + 188) continue;
            p += 1 + (data[p] & 255);
            if (p + 12 >= i + 188 || data[p] != 2) continue;
            int end = p + 3 + (((data[p + 1] & 15) << 8) | (data[p + 2] & 255)) - 4;
            if (end > i + 188) continue;
            int entry = p + 12 + (((data[p + 10] & 15) << 8) | (data[p + 11] & 255));
            while (entry + 5 <= end) {
                int type = data[entry] & 255;
                if (type == 0x24 || type == 0x42)
                    throw new IOException("O vídeo usa HEVC ou AVS. Escolha uma fonte H.264; juntar segmentos não muda o codec.");
                entry += 5 + (((data[entry + 3] & 15) << 8) | (data[entry + 4] & 255));
            }
        }
    }

    /**
     * Recover a VOD segment when a CDN URL became stale. A refreshed manifest is
     * accepted only when it still describes the SAME media sequence and durations.
     * Never skip segments or silently substitute another position of the movie.
     */
    private byte[] segmentAt(int index) throws Exception {
        Segment original = initial.segments.get(index);
        try { return segment(original); }
        catch (IOException failure) {
            if (!initial.ended) throw failure;
            Playlist refreshed;
            try { refreshed = load(initial.url, 0); }
            catch (Exception e) {
                android.util.Log.w("VieraFlow", "HLS playlist refresh failed; original segment error preserved", e);
                throw failure;
            }
            if (!refreshed.ended || refreshed.segments.size() != initial.segments.size())
                throw new IOException("A fonte mudou a sequência de segmentos. Trecho " + (index + 1)
                        + "/" + initial.segments.size() + ". Não vou pular o filme.", failure);
            for (int j = 0; j < initial.segments.size(); j++) {
                Segment old = initial.segments.get(j), now = refreshed.segments.get(j);
                if (old.sequence != now.sequence || old.durationMs != now.durationMs)
                    throw new IOException("A playlist mudou de conteúdo ao renovar. Trecho "
                            + (index + 1) + ".", failure);
            }
            Segment current = refreshed.segments.get(index);
            if (original.url.toString().equals(current.url.toString())
                    && java.util.Objects.equals(original.range, current.range)
                    && Arrays.equals(original.key, current.key)
                    && Arrays.equals(original.iv, current.iv)) throw failure;
            android.util.Log.i("VieraFlow", "HLS refreshed signed URL at segment " + (index + 1)
                    + "/" + initial.segments.size());
            return segment(current);
        }
    }

    /** Producer/consumer decouples CDN fetches from the TV socket; queue is bounded. */
    private void writeVod(OutputStream out) throws Exception {
        final ArrayBlockingQueue<QueuedSegment> ready = new ArrayBlockingQueue<>(QUEUE_SEGMENTS);
        final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();
        Thread producer = new Thread(() -> {
            int index = 0;
            try {
                for (; index < initial.segments.size() && !stopped.get(); index++) {
                    byte[] bytes = index < bootstrap.size() ? bootstrap.get(index) : segmentAt(index);
                    while (!stopped.get() && !ready.offer(new QueuedSegment(bytes, null), 250, TimeUnit.MILLISECONDS)) { }
                }
            } catch (Exception cause) {
                long elapsedMs = durationMs(0, index);
                Exception error = new IOException("Segmento " + (index + 1) + "/" + initial.segments.size()
                        + " em " + formatTime(elapsedMs) + ": " + cause.getMessage(), cause);
                while (!stopped.get()) {
                    try { if (ready.offer(new QueuedSegment(null, error), 250, TimeUnit.MILLISECONDS)) break; }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                }
            }
        }, "VieraFlow-HLS-prefetch");
        producer.setDaemon(true);
        producer.start();
        try {
            long elapsedMs = 0;
            for (int i = 0; i < initial.segments.size(); i++) {
                QueuedSegment queued = ready.take();
                if (queued.failure != null) throw queued.failure;
                out.write(queued.bytes);
                out.flush();
                elapsedMs += initial.segments.get(i).durationMs;
                ProgressListener callback = progressListener;
                if (callback != null) callback.segment(i + 1, initial.segments.size(), elapsedMs);
            }
        } finally {
            stopped.set(true);
            producer.interrupt();
        }
    }
    private static final class QueuedSegment {
        final byte[] bytes; final Exception failure;
        QueuedSegment(byte[] bytes, Exception failure) { this.bytes = bytes; this.failure = failure; }
    }

    void write(OutputStream out) throws Exception {
        if (initial.ended) { writeVod(out); return; }
        Playlist playlist = initial; long next = playlist.segments.get(0).sequence;
        long lastProgress = System.nanoTime(); boolean firstWrite = true;
        while (true) {
            for (Segment segment : playlist.segments) {
                if (segment.sequence < next) continue;
                if (segment.sequence != next) throw new IOException("A rede ficou atrasada e perdeu segmentos da transmissão ao vivo.");
                if (Thread.currentThread().isInterrupted()) throw new IOException("Transmissão encerrada.");
                out.write(firstWrite ? first : segment(segment)); out.flush();
                firstWrite = false; next++; lastProgress = System.nanoTime();
            }
            if (playlist.ended) return;
            if (System.nanoTime() - lastProgress > 90000000000L) throw new IOException("A transmissão ao vivo deixou de atualizar.");
            Thread.sleep(playlist.intervalMs);
            playlist = load(playlist.url, 0);
        }
    }

    static String formatTime(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }
    long totalDurationMs() { return durationMs(0, initial.segments.size()); }
    int segmentCount() { return initial.segments.size(); }
    boolean isComplete() { return initial.ended; }

    /** Total EXTINF duration of a window of segments. */
    long durationMs(int start, int count) {
        long total = 0;
        for (int i = start; i < start + count && i < initial.segments.size(); i++) total += initial.segments.get(i).durationMs;
        return total;
    }

    int segmentsForDuration(int start, int targetMs, int maxCount) {
        int duration = 0, count = 0;
        for (int i = start; i < initial.segments.size() && count < maxCount; i++) {
            duration += initial.segments.get(i).durationMs;
            count++;
            if (duration >= targetMs) break;
        }
        return count;
    }

    /** Writes only a bounded VOD window, keeping the cached first segment. */
    void writeSegments(OutputStream out, int start, int count) throws Exception {
        if (!initial.ended) throw new IOException("Esta fonte HLS é ao vivo. Selecione um vídeo sob demanda.");
        if (start < 0 || count <= 0 || start + count > initial.segments.size())
            throw new IOException("Trecho HLS fora da playlist.");
        for (int i = start; i < start + count; i++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Preparação cancelada.");
            out.write(i == 0 ? first : segment(initial.segments.get(i)));
        }
    }
}
