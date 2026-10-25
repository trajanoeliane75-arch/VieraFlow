package br.dev.sebasty.vieraflow;

import android.content.Context;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** A receiver that refuses replacing paused/playing media, as observed on the user's VIERA. */
public class TransportTest {
    static String state, mode;
    static int sets, stops, plays, remainingStopPolls;
    static String lastSeekTarget;
    static final List<String> events = new ArrayList<>();
    static final AtomicBoolean current = new AtomicBoolean(true);
    static int passed;

    static void reset(String initialState, String behavior) {
        state = initialState; mode = behavior; sets = stops = plays = remainingStopPolls = 0;
        current.set(true); events.clear(); lastSeekTarget = null;
    }
    static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label + " " + events);
        passed++; System.out.println("PASS " + label);
    }
    static String play(DlnaController controller, DlnaController.Device device) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        controller.play(device, "http://127.0.0.1/new.mp4", "Replacement", "video/mp4", current::get,
                error -> { result.set(error); done.countDown(); });
        if (!done.await(15, TimeUnit.SECONDS)) throw new AssertionError("Transport command timed out");
        return result.get();
    }
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/soap", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("SOAPACTION");
            String action = header.substring(header.indexOf('#') + 1).replace("\"", "");
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            events.add(action);
            String payload = "", code = "";
            if (action.equals("Stop")) {
                stops++;
                if (mode.equals("stopLocked")) code = "705";
                else { state = "TRANSITIONING"; remainingStopPolls = 2; }
                if (mode.equals("cancelStop")) current.set(false);
            } else if (action.equals("GetTransportInfo")) {
                if (state.equals("TRANSITIONING") && remainingStopPolls > 0 && !mode.equals("neverStops")) {
                    if (--remainingStopPolls == 0) state = "STOPPED";
                }
                payload = "<u:GetTransportInfoResponse xmlns:u='urn:schemas-upnp-org:service:AVTransport:1'>"
                        + "<CurrentTransportState>" + state + "</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus></u:GetTransportInfoResponse>";
            } else if (action.equals("SetAVTransportURI")) {
                sets++;
                if (!(state.equals("STOPPED") || state.equals("NO_MEDIA_PRESENT"))
                        || mode.equals("alwaysLocked") || (mode.equals("lockedOnce") && sets == 1)) code = "705";
                else if (mode.equals("format")) code = "704";
                else if (mode.equals("cancelSet")) current.set(false);
            } else if (action.equals("GetPositionInfo")) {
                if (mode.equals("rejectPosition")) code = "401";
                String rel = mode.equals("nullTime") ? "NOT_IMPLEMENTED" : "00:01:12";
                payload = "<u:GetPositionInfoResponse xmlns:u='urn:schemas-upnp-org:service:AVTransport:1'>"
                        + "<RelTime>" + rel + "</RelTime><TrackDuration>00:22:00</TrackDuration>"
                        + "</u:GetPositionInfoResponse>";
            } else if (action.equals("Seek")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("<Target>([^<]+)</Target>").matcher(requestBody);
                if (m.find()) lastSeekTarget = m.group(1);
                if (mode.equals("rejectSeek")) code = "710";
            } else if (action.equals("Play")) { plays++; state = "PLAYING"; }
            if (!code.isEmpty()) payload = "<s:Fault><detail><UPnPError><errorCode>" + code + "</errorCode></UPnPError></detail></s:Fault>";
            byte[] bytes = ("<s:Envelope xmlns:s='http://schemas.xmlsoap.org/soap/envelope/'><s:Body>" + payload + "</s:Body></s:Envelope>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code.isEmpty() ? 200 : 500, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        DlnaController controller = new DlnaController(new Context());
        DlnaController.Device tv = new DlnaController.Device("fixture", "http://127.0.0.1:" + server.getAddress().getPort() + "/soap", "urn:schemas-upnp-org:service:AVTransport:1");
        try {
            for (String initial : new String[]{"PAUSED_PLAYBACK", "PLAYING", "TRANSITIONING"}) {
                reset(initial, "ok");
                check(play(controller, tv) == null && stops == 1 && sets == 1 && plays == 1,
                        initial + " waits for stopped before replacing media");
                check(events.indexOf("Stop") < events.indexOf("SetAVTransportURI"), "Stop precedes URI replacement");
            }
            reset("NO_MEDIA_PRESENT", "ok");
            check(play(controller, tv) == null && stops == 0, "Empty renderer does not receive unnecessary Stop");
            reset("STOPPED", "lockedOnce");
            check(play(controller, tv) == null && sets == 2 && stops == 1 && plays == 1, "Stale 705 recovers with one stop and one retry");
            reset("STOPPED", "alwaysLocked");
            check(play(controller, tv).contains("705") && sets == 2 && stops == 1 && plays == 0, "Persistent 705 remains an error without a retry loop");
            reset("PAUSED_PLAYBACK", "stopLocked");
            check(play(controller, tv).contains("705") && sets == 0 && plays == 0, "Rejected Stop cannot start replacement");
            reset("PLAYING", "neverStops");
            check(play(controller, tv).contains("não confirmou") && sets == 0 && plays == 0, "Stop acknowledgement without stopped state cannot replace media");
            reset("STOPPED", "format");
            check(play(controller, tv).contains("704") && sets == 1 && plays == 0, "Format error is not treated as transport lock");
            reset("PAUSED_PLAYBACK", "cancelStop");
            check(play(controller, tv).contains("cancelado") && sets == 0 && plays == 0, "Cancellation during stop prevents stale media switch");
            reset("STOPPED", "cancelSet");
            check(play(controller, tv).contains("cancelado") && plays == 0, "Cancellation after URI prevents stale Play");
            reset("STOPPED", "ok");
            check(play(controller, tv) == null, "New command succeeds after canceled operation");
            reset("RECORDING", "ok");
            check(play(controller, tv).contains("gravando") && stops == 0 && sets == 0, "Recording is not interrupted");
            check(DlnaController.parseClock("01:02:03.250") == 3723250L, "DLNA clock fractions parsed");
            check(DlnaController.parseClock("NOT_IMPLEMENTED") < 0, "Invalid DLNA clock remains unknown");
            check(DlnaController.clock(3723250L).equals("01:02:03"), "DLNA clock formatted");
            reset("PLAYING", "ok");
            CountDownLatch posDone = new CountDownLatch(1);
            AtomicReference<DlnaController.Position> position = new AtomicReference<>();
            controller.getPosition(tv, (p, error) -> { position.set(p); posDone.countDown(); });
            check(posDone.await(4, TimeUnit.SECONDS) && position.get() != null
                    && position.get().elapsedMs == 72000L && position.get().durationMs == 1320000L,
                    "GetPositionInfo returns renderer time");
            CountDownLatch seekDone = new CountDownLatch(1);
            AtomicReference<String> seekError = new AtomicReference<>();
            controller.seekBy(tv, 30000, error -> { seekError.set(error); seekDone.countDown(); });
            check(seekDone.await(4, TimeUnit.SECONDS) && seekError.get() == null
                    && "00:01:42".equals(lastSeekTarget), "Seek uses REL_TIME +30 seconds");
            reset("PLAYING", "nullTime");
            CountDownLatch invalidDone = new CountDownLatch(1);
            AtomicReference<String> invalidError = new AtomicReference<>();
            controller.seekBy(tv, 30000, error -> { invalidError.set(error); invalidDone.countDown(); });
            check(invalidDone.await(4, TimeUnit.SECONDS) && invalidError.get() != null
                    && !events.contains("Seek"), "Unknown position must not send seek");
            reset("PLAYING", "rejectSeek");
            CountDownLatch rejectDone = new CountDownLatch(1);
            AtomicReference<String> rejectError = new AtomicReference<>();
            controller.seekBy(tv, 30000, error -> { rejectError.set(error); rejectDone.countDown(); });
            check(rejectDone.await(4, TimeUnit.SECONDS) && rejectError.get() != null
                    && rejectError.get().contains("710"), "Renderer seek rejection is reported");
            PlaybackState observed = new PlaybackState();
            for (String receiverState : new String[]{"PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING", "STOPPED", "NO_MEDIA_PRESENT"}) {
                reset(receiverState, "rejectPosition");
                CountDownLatch observedDone = new CountDownLatch(1);
                AtomicReference<DlnaController.Playback> snapshot = new AtomicReference<>();
                controller.getPlayback(tv, (p, error) -> { snapshot.set(p); observedDone.countDown(); });
                check(observedDone.await(4, TimeUnit.SECONDS) && snapshot.get() != null
                        && receiverState.equals(snapshot.get().state), "Transport state survives unavailable position: " + receiverState);
                observed.update(snapshot.get());
                boolean ended = receiverState.equals("STOPPED") || receiverState.equals("NO_MEDIA_PRESENT");
                check(observed.terminal() == ended && observed.active() != ended,
                        "UI follows actual receiver state: " + receiverState);
                check(!events.contains("Stop") && !events.contains("Play"), "Monitoring never controls playback");
            }
            observed.update(new DlnaController.Playback("PLAYING", "OK", null));
            observed.update(null);
            check(!observed.active() && !observed.terminal() && observed.label().contains("verificando"),
                    "One failed query clears stale playing without declaring a stop");
            observed.update(null); observed.update(null);
            check(observed.label().contains("sem resposta") && !observed.terminal(),
                    "Repeated failures show unreachable without destroying the stream");
            observed.update(new DlnaController.Playback("PLAYING", "OK", null));
            check(observed.active() && observed.label().contains("reproduzindo"), "Monitoring recovers after connectivity returns");
            observed.update(new DlnaController.Playback("PLAYING", "ERROR_OCCURRED", null));
            check(observed.terminal() && !observed.active(), "Receiver error overrides stale PLAYING state");
            reset("", "ok");
            CountDownLatch missingDone = new CountDownLatch(1);
            AtomicReference<String> missingError = new AtomicReference<>();
            controller.getPlayback(tv, (p, error) -> { missingError.set(error); missingDone.countDown(); });
            check(missingDone.await(4, TimeUnit.SECONDS) && missingError.get() != null,
                    "Missing receiver state is an error, not stopped");
            System.out.println("TOTAL " + passed + " transport assertions passed");
        } finally { controller.close(); server.stop(0); }
    }
}
