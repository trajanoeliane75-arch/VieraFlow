package br.dev.sebasty.vieraflow;

import android.content.Context;
import android.net.wifi.WifiManager;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import javax.xml.parsers.DocumentBuilderFactory;

/** UPnP AVTransport controller, without Chromecast or screen mirroring. */
public final class DlnaController {
    public static final class Device {
        public final String name;
        public final String controlUrl;
        public final String serviceType;
        public final String volumeUrl;
        public final String volumeType;
        public final String remoteUrl;
        Device(String name, String controlUrl, String serviceType) {
            this(name, controlUrl, serviceType, null, null, null);
        }
        Device(String name, String controlUrl, String serviceType, String volumeUrl, String volumeType, String remoteUrl) {
            this.name = name; this.controlUrl = controlUrl; this.serviceType = serviceType;
            this.volumeUrl = volumeUrl; this.volumeType = volumeType; this.remoteUrl = remoteUrl;
        }
        @Override public String toString() { return name; }
    }
    public interface DiscoveryListener { void found(Device device); void finished(String error); }
    public interface ResultListener { void completed(String error); }
    public interface DirectConnectListener { void completed(Device device, String error); }
    public interface PositionListener { void updated(Position position, String error); }
    public static final class Position {
        public final long elapsedMs;
        public final long durationMs;
        Position(long elapsedMs, long durationMs) {
            this.elapsedMs = elapsedMs; this.durationMs = durationMs;
        }
    }
    /** AVTransport time values are not always present on legacy DLNA renderers. */
    static long parseClock(String value) {
        if (value == null || !value.matches("[0-9]+:[0-5][0-9]:[0-5][0-9](\\.[0-9]+)?")) return -1;
        String[] items = value.split("[:.]", 4);
        try {
            long base = (Long.parseLong(items[0]) * 3600L + Long.parseLong(items[1]) * 60L
                    + Long.parseLong(items[2])) * 1000L;
            return base + (items.length > 3 ? Long.parseLong((items[3] + "000").substring(0, 3)) : 0);
        } catch (NumberFormatException ex) { return -1; }
    }
    static String clock(long value) {
        long seconds = Math.max(0, value / 1000);
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", seconds / 3600,
                (seconds / 60) % 60, seconds % 60);
    }
    private static Position readPosition(Device device) throws Exception {
        String response = soap(device, "GetPositionInfo", "<InstanceID>0</InstanceID>");
        Document doc = parse(new java.io.ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)));
        Element element = doc.getDocumentElement();
        return new Position(parseClock(childText(element, "RelTime")),
                parseClock(childText(element, "TrackDuration")));
    }
    /** Read a receiver-reported playback position (not bytes buffered by the phone). */
    public void getPosition(Device device, PositionListener listener) {
        background.execute(() -> {
            Position result = null; String error = null;
            try { result = readPosition(device); }
            catch (Exception ex) { error = ex.getMessage(); }
            listener.updated(result, error);
        });
    }
    /** Seek is only attempted when the renderer reports a real relative time. */
    public void seekBy(Device device, long deltaMs, ResultListener listener) {
        commands.execute(() -> {
            String error = null;
            try {
                Position position = readPosition(device);
                if (position.elapsedMs < 0)
                    throw new IOException("A TV não disponibiliza a posição deste vídeo; não é seguro avançar.");
                long target = Math.max(0, position.elapsedMs + deltaMs);
                if (position.durationMs > 0) target = Math.min(target, Math.max(0, position.durationMs - 1000));
                soap(device, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>"
                        + clock(target) + "</Target>");
            } catch (Exception ex) { error = ex.getMessage(); }
            listener.completed(error);
        });
    }

    private final Context context;
    private final ExecutorService background = Executors.newCachedThreadPool();
    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    public interface ProgressListener { void update(String message); }
    private volatile ProgressListener progress;
    public void setProgressListener(ProgressListener listener) { progress = listener; }
    public DlnaController(Context context) { this.context = context.getApplicationContext(); }

    /** Panasonic Viera direct DMR description, useful when SSDP multicast is blocked. */
    public void connectPanasonicByIp(String ipv4, DirectConnectListener listener) {
        background.execute(() -> {
            Device device = null;
            String error = null;
            try {
                device = readDescription("http://" + ipv4 + ":55000/dmr/ddd.xml");
                if (device == null) error = "O endereço respondeu, mas não anunciou um receptor DLNA/DMR.";
            } catch (Exception e) {
                error = "Não consegui ler o receptor em " + ipv4 + ":55000 — "
                        + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            listener.completed(device, error);
        });
    }

    public void discover(DiscoveryListener listener) {
        background.execute(() -> {
            String problem = null;
            WifiManager.MulticastLock multicastLock = null;
            Set<String> seen = new HashSet<>();
            int responses = 0;
            int renderers = 0;
            try {
                WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    multicastLock = wifi.createMulticastLock("vieraflow-ssdp");
                    multicastLock.setReferenceCounted(false);
                    multicastLock.acquire();
                }
                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.setSoTimeout(750);
                    InetAddress ssdp = InetAddress.getByName("239.255.255.250");
                    long until = System.currentTimeMillis() + 6700;
                    for (String target : new String[]{"urn:schemas-upnp-org:device:MediaRenderer:1", "ssdp:all"}) {
                        byte[] query = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + target + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(query, query.length, ssdp, 1900));
                    }
                    while (System.currentTimeMillis() < until) {
                        byte[] bytes = new byte[16384];
                        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                        try { socket.receive(packet); } catch (SocketTimeoutException ignored) { continue; }
                        responses++;
                        String response = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                        String location = header(response, "location");
                        if (location == null || !seen.add(location)) continue;
                        try {
                            Device device = readDescription(location);
                            if (device != null) { renderers++; listener.found(device); }
                        } catch (Exception e) { android.util.Log.w("VieraFlow", "UPnP description rejected: " + location, e); }
                    }
                }
                if (renderers == 0) {
                    if (responses == 0) problem = "Nenhum aparelho respondeu à descoberta SSDP (UDP 1900).";
                    else problem = "A rede respondeu ao SSDP, mas não informou um receptor DLNA/DMR. Respostas: " + responses + ", descrições: " + seen.size() + ".";
                }
            } catch (Exception e) { problem = "Erro ao pesquisar a rede: " + e.getClass().getSimpleName() + " — " + e.getMessage(); }
            finally {
                if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
                listener.finished(problem);
            }
        });
    }

    private static String header(String response, String key) {
        for (String line : response.split("\r?\n")) {
            int sep = line.indexOf(':');
            if (sep > 0 && key.equalsIgnoreCase(line.substring(0, sep).trim())) return line.substring(sep + 1).trim();
        }
        return null;
    }
    private static Document parse(InputStream in) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // O parser XML do Android não suporta todos os "features" do Java desktop: ignora os que falharem.
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        try { factory.setExpandEntityReferences(false); } catch (Exception ignored) { }
        return factory.newDocumentBuilder().parse(in);
    }
    private static void trySetFeature(DocumentBuilderFactory factory, String name, boolean value) {
        try { factory.setFeature(name, value); } catch (Exception ignored) { /* não suportado no Android */ }
    }
    private static String childText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
    private static Device readDescription(String location) throws Exception {
        URL base = new URL(location);
        if (!"http".equalsIgnoreCase(base.getProtocol())) return null;
        HttpURLConnection connection = (HttpURLConnection) base.openConnection();
        connection.setConnectTimeout(2300); connection.setReadTimeout(2300);
        try (InputStream in = connection.getInputStream()) {
            Document document = parse(in);
            String deviceType = childText(document.getDocumentElement(), "deviceType");
            if (!deviceType.contains("MediaRenderer")) return null;
            String name = childText(document.getDocumentElement(), "friendlyName");
            String urlBase = childText(document.getDocumentElement(), "URLBase");
            if (!urlBase.isEmpty()) base = new URL(urlBase);
            NodeList services = document.getElementsByTagName("service");
            String transportUrl = null, transportType = null, volumeUrl = null, volumeType = null;
            for (int i = 0; i < services.getLength(); i++) {
                Element service = (Element) services.item(i);
                String type = childText(service, "serviceType");
                if (type.contains(":AVTransport:") || type.contains(":RenderingControl:")) {
                    URL control = new URL(base, childText(service, "controlURL"));
                    if (!"http".equalsIgnoreCase(control.getProtocol())) continue;
                    if (type.contains(":AVTransport:")) { transportUrl = control.toString(); transportType = type; }
                    else { volumeUrl = control.toString(); volumeType = type; }
                }
            }
            if (transportUrl != null) return new Device(name.isEmpty() ? "Televisor DLNA" : name,
                    transportUrl, transportType, volumeUrl, volumeType, new URL(base, "/nrc/control_0").toString());
        } finally { connection.disconnect(); }
        return null;
    }

    public void play(Device device, String mediaUrl, String title, String contentType, ResultListener listener) {
        play(device, mediaUrl, title, contentType, () -> true, listener);
    }
    public void play(Device device, String mediaUrl, String title, String contentType,
                     BooleanSupplier current, ResultListener listener) {
        commands.execute(() -> {
            String error = null;
            try {
                checkCurrent(current);
                String didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                        + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                        + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                        + "<item id=\"1\" parentID=\"0\" restricted=\"1\">"
                        + "<dc:title>" + escapeXml(title) + "</dc:title>"
                        + "<upnp:class>object.item.videoItem</upnp:class>"
                        + "<res protocolInfo=\"http-get:*:" + escapeXml(contentType) + ":" + escapeXml(contentType.equals("video/mpeg")
                                ? StreamProxy.DLNA_TS : "*") + "\">"
                        + escapeXml(mediaUrl) + "</res></item></DIDL-Lite>";
                String args = "<InstanceID>0</InstanceID><CurrentURI>" + escapeXml(mediaUrl)
                        + "</CurrentURI><CurrentURIMetaData>" + escapeXml(didl) + "</CurrentURIMetaData>";
                // Some VIERAs advertise only Stop/Play while paused, and reject URI changes with 705.
                String oldState = readTransportState(device);
                checkCurrent(current);
                if (!"STOPPED".equals(oldState) && !"NO_MEDIA_PRESENT".equals(oldState)) {
                    if (oldState.contains("RECORDING")) throw new IOException("A TV está gravando. Encerre a gravação antes de enviar um vídeo.");
                    stopForReplacement(device, current);
                }
                checkCurrent(current);
                try {
                    soap(device, "SetAVTransportURI", args);
                } catch (TransportLockedException locked) {
                    // A stopped renderer can still be releasing its old media. Recover once, never loop.
                    stopForReplacement(device, current);
                    checkCurrent(current);
                    soap(device, "SetAVTransportURI", args);
                }
                checkCurrent(current);
                soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
                boolean playing = false;
                for (int attempt = 0; attempt < 60; attempt++) {
                    checkCurrent(current);
                    String response = soap(device, "GetTransportInfo", "<InstanceID>0</InstanceID>");
                    Document state = parse(new java.io.ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)));
                    String status = childText(state.getDocumentElement(), "CurrentTransportStatus");
                    if ("ERROR_OCCURRED".equals(status)) throw new IOException("A TV informou um erro ao abrir o vídeo.");
                    if ("PLAYING".equals(childText(state.getDocumentElement(), "CurrentTransportState"))) { playing = true; break; }
                    Thread.sleep(500);
                }
                if (!playing) throw new IOException("A TV recebeu o pedido, mas não confirmou a reprodução. A fonte ou o codec pode ser incompatível.");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); error = "Envio cancelado."; }
            catch (Exception e) { error = e.getMessage(); }
            listener.completed(error);
        });
    }
    private static void checkCurrent(BooleanSupplier current) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Envio cancelado.");
        if (!current.getAsBoolean()) throw new java.util.concurrent.CancellationException("Envio cancelado.");
    }
    private static String readTransportState(Device device) throws Exception {
        String response = soap(device, "GetTransportInfo", "<InstanceID>0</InstanceID>");
        Document state = parse(new java.io.ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)));
        String value = childText(state.getDocumentElement(), "CurrentTransportState");
        if (value.isEmpty()) throw new IOException("A TV não informou o estado do player.");
        return value;
    }
    private void stopForReplacement(Device device, BooleanSupplier current) throws Exception {
        checkCurrent(current);
        if (progress != null) progress.update("Liberando o vídeo anterior na TV…");
        soap(device, "Stop", "<InstanceID>0</InstanceID>");
        long deadline = System.nanoTime() + 6000000000L;
        while (System.nanoTime() < deadline) {
            checkCurrent(current);
            String state = readTransportState(device);
            if ("STOPPED".equals(state) || "NO_MEDIA_PRESENT".equals(state)) {
                Thread.sleep(250); // Let the receiver finish closing the previous HTTP request.
                checkCurrent(current);
                return;
            }
            Thread.sleep(250);
        }
        throw new IOException("A TV não confirmou que parou o vídeo anterior. Feche o player pelo controle da TV e envie novamente.");
    }
    public void command(Device device, String action, ResultListener listener) {
        commands.execute(() -> {
            String error = null;
            try {
                if (!"Play".equals(action) && !"Pause".equals(action) && !"Stop".equals(action)) throw new IllegalArgumentException("Ação inválida");
                String args = "<InstanceID>0</InstanceID>" + ("Play".equals(action) ? "<Speed>1</Speed>" : "");
                soap(device, action, args);
            } catch (Exception e) { error = e.getMessage(); }
            listener.completed(error);
        });
    }

    public void adjustVolume(Device device, int delta, ResultListener listener) {
        commands.execute(() -> {
            String error = null;
            try {
                if (device.volumeUrl == null) throw new IOException("A TV não anunciou controle de volume.");
                String reply = soap(device.volumeUrl, device.volumeType, "GetVolume",
                        "<InstanceID>0</InstanceID><Channel>Master</Channel>");
                Document doc = parse(new java.io.ByteArrayInputStream(reply.getBytes(StandardCharsets.UTF_8)));
                int current = Integer.parseInt(childText(doc.getDocumentElement(), "CurrentVolume"));
                int target = Math.max(0, Math.min(100, current + delta));
                soap(device.volumeUrl, device.volumeType, "SetVolume",
                        "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>" + target + "</DesiredVolume>");
            } catch (Exception e) { error = e.getMessage(); }
            listener.completed(error);
        });
    }
    public void toggleMute(Device device, ResultListener listener) {
        commands.execute(() -> {
            String error = null;
            try {
                if (device.volumeUrl == null) throw new IOException("A TV não anunciou controle de áudio.");
                String reply = soap(device.volumeUrl, device.volumeType, "GetMute",
                        "<InstanceID>0</InstanceID><Channel>Master</Channel>");
                Document doc = parse(new java.io.ByteArrayInputStream(reply.getBytes(StandardCharsets.UTF_8)));
                boolean muted = "1".equals(childText(doc.getDocumentElement(), "CurrentMute"));
                soap(device.volumeUrl, device.volumeType, "SetMute",
                        "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>" + (muted ? "0" : "1") + "</DesiredMute>");
            } catch (Exception e) { error = e.getMessage(); }
            listener.completed(error);
        });
    }
    public void remoteKey(Device device, String key, ResultListener listener) {
        commands.execute(() -> {
            String error = null;
            try {
                if (device.remoteUrl == null || !key.matches("NRC_[A-Z0-9_]+-ONOFF"))
                    throw new IOException("Comando remoto inválido.");
                soap(device.remoteUrl, "urn:panasonic-com:service:p00NetworkControl:1", "X_SendKey",
                        "<X_KeyEvent>" + key + "</X_KeyEvent>");
            } catch (Exception e) { error = e.getMessage(); }
            listener.completed(error);
        });
    }

    /** Called only from a background worker while a short HLS clip is playing. */
    public String transportState(Device device) throws Exception {
        String response = soap(device, "GetTransportInfo", "<InstanceID>0</InstanceID>");
        Document state = parse(new java.io.ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)));
        String status = childText(state.getDocumentElement(), "CurrentTransportStatus");
        if ("ERROR_OCCURRED".equals(status)) throw new IOException("A TV interrompeu a reprodução.");
        return childText(state.getDocumentElement(), "CurrentTransportState");
    }
    private static final class TransportLockedException extends IOException {
        TransportLockedException() { super("A TV manteve o player bloqueado (código 705). Feche o player pelo controle da TV e envie novamente."); }
    }
    private static String soap(Device device, String action, String args) throws Exception {
        return soap(device.controlUrl, device.serviceType, action, args);
    }
    private static String soap(String controlUrl, String serviceType, String action, String args) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + escapeXml(serviceType) + "\">"
                + args + "</u:" + action + "></s:Body></s:Envelope>";
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(controlUrl).openConnection();
        connection.setConnectTimeout(6000); connection.setReadTimeout(9000);
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        connection.setRequestProperty("SOAPACTION", "\"" + serviceType + "#" + action + "\"");
        connection.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                InputStream err = connection.getErrorStream();
                if (err != null) try (InputStream in = err) {
                    byte[] bytes = new byte[1024]; int n;
                    while (buf.size() < 4096 && (n = in.read(bytes)) > 0) buf.write(bytes, 0, n);
                }
                String code = "", description = "";
                try {
                    Document fault = parse(new java.io.ByteArrayInputStream(buf.toByteArray()));
                    code = childText(fault.getDocumentElement(), "errorCode");
                    description = childText(fault.getDocumentElement(), "errorDescription");
                } catch (Exception ignored) { }
                if ("705".equals(code)) throw new TransportLockedException();
                if ("704".equals(code) || "714".equals(code))
                    throw new IOException("A TV recusou o formato (" + code + "). Converter HLS em MPEG-TS não altera o codec. Tente outra fonte em H.264/AAC ou o vídeo de teste.");
                throw new IOException("TV recusou " + action + " (HTTP " + status + (code.isEmpty() ? "" : ", código " + code) + ") " + description);
            }
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] bytes = new byte[1024]; int n;
                while ((n = in.read(bytes)) != -1) {
                    if (result.size() + n > 1024 * 1024) throw new IOException("Resposta da TV grande demais.");
                    result.write(bytes, 0, n);
                }
            }
            return result.toString("UTF-8");
        } finally { connection.disconnect(); }
    }
    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
    public void close() { background.shutdownNow(); commands.shutdownNow(); }
}
