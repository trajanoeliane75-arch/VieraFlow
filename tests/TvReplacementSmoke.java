package br.dev.sebasty.vieraflow;

import android.content.Context;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit manual test: replaces current TV media, tests paused/playing replacement, then stops. */
public class TvReplacementSmoke {
    static void require(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + label);
    }
    static void command(DlnaController c, DlnaController.Device tv, String action) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        c.command(tv, action, e -> { result.set(e); done.countDown(); });
        if (!done.await(20, TimeUnit.SECONDS)) throw new Exception(action + " timeout");
        if (result.get() != null) throw new Exception(action + ": " + result.get());
    }
    static void play(DlnaController c, DlnaController.Device tv, StreamProxy.Media media, String name) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        c.play(tv, media.url, name, media.mime, e -> { result.set(e); done.countDown(); });
        if (!done.await(45, TimeUnit.SECONDS)) throw new Exception(name + " timeout");
        if (result.get() != null) throw new Exception(name + ": " + result.get());
        require("PLAYING".equals(c.transportState(tv)), name + " reports PLAYING");
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !"--replace-current".equals(args[2])) throw new IllegalArgumentException("fixture_directory TV_IP --replace-current");
        byte[] sample = Files.readAllBytes(Paths.get(args[0], "sample.mp4"));
        for (int i = 1; i <= 3; i++) IntegrationTest.files.put("/replacement" + i + ".mp4", sample);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        ExecutorService pool = Executors.newCachedThreadPool(); server.setExecutor(pool);
        server.createContext("/", IntegrationTest::handle); server.start();
        StreamProxy proxy = new StreamProxy(new Context(), false);
        DlnaController c = new DlnaController(new Context());
        c.setProgressListener(System.out::println);
        DlnaController.Device tv = new DlnaController.Device("SEBASTY", "http://" + args[1] + ":55000/dmr/control_2", "urn:schemas-upnp-org:service:AVTransport:1");
        try {
            System.out.println("BEFORE " + c.transportState(tv));
            proxy.start("", "VieraFlow-0.1.8-validation");
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            play(c, tv, proxy.prepare(origin + "/replacement1.mp4"), "VieraFlow teste 1");
            command(c, tv, "Pause");
            require("PAUSED_PLAYBACK".equals(c.transportState(tv)), "TV paused on first test video");
            play(c, tv, proxy.prepare(origin + "/replacement2.mp4"), "VieraFlow teste 2 depois de pausa");
            play(c, tv, proxy.prepare(origin + "/replacement3.mp4"), "VieraFlow teste 3 durante reproducao");
            Thread.sleep(1500);
            require("PLAYING".equals(c.transportState(tv)), "Replacement keeps playing");
        } finally {
            try { command(c, tv, "Stop"); System.out.println("AFTER " + c.transportState(tv)); }
            finally { proxy.close(); c.close(); server.stop(0); pool.shutdownNow(); }
        }
    }
}
