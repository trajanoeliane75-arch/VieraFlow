package br.dev.sebasty.vieraflow;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.os.Message;
import android.widget.SeekBar;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.text.Editable;
import org.json.JSONTokener;
import org.json.JSONArray;
import android.net.Uri;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Full native Android shell; phone controls media, TV decodes it via DLNA. */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(246, 247, 251);
    private static final int SURFACE = Color.WHITE;
    private static final int BLUE = Color.rgb(0, 101, 242);
    private static final int INK = Color.rgb(24, 28, 35);
    private static final int MUTED = Color.rgb(116, 119, 130);
    private static final int LINE = Color.rgb(231, 233, 241);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<DlnaController.Device> tvs = new ArrayList<>();
    private DlnaController controller;
    private DlnaController.Device tv;
    private StreamProxy proxy;
    private WebView browser;
    private EditText address;
    private TextView status;
    private TextView deviceLabel;
    private TextView discoverButton;
    private TextView candidateLabel;
    private TextView sendButton;
    private AlertDialog discoveryDialog;
    private boolean discovering;
    private boolean preparing;
    private boolean browserSuspended;
    private volatile int queueGeneration;
    private final java.util.concurrent.ExecutorService mediaWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private Future<?> mediaTask;
    private String candidateUrl;
    private final List<String> detectedUrls = new ArrayList<>();
    private final Map<String, String> detectedReferers = new HashMap<>();
    private boolean manualCandidate;
    private boolean suppressRedirects = true;
    private boolean pendingEpisodeLookup, pendingManualScan;
    private long pageLoadedAt;
    private String castingTitle = "";
    private String castingPage = "";
    private volatile boolean tvPlaying;
    private volatile boolean proxyStreamIssue;
    private volatile String proxyIssueMessage = "";
    private volatile boolean positionPending;
    private long lastTvElapsedMs = -1;
    private long lastTvDurationMs = -1;
    private TextView timeLabel;
    private View chromeHeadline, chromeConnection, chromeFooter, chromeStatus;
    private TextView expandButton;
    private boolean browserExpanded;
    private final PlaybackState playbackState = new PlaybackState();
    private boolean localCastActive;
    private int playbackRevision;
    private boolean transportCommandPending;
    private final Runnable pollTvPosition = new Runnable() {
        @Override public void run() {
            if (tv != null && !preparing && !transportCommandPending && !positionPending) {
                final DlnaController.Device device = tv;
                final int generation = queueGeneration, revision = playbackRevision;
                positionPending = true;
                controller.getPlayback(device, (playback, error) -> runOnUiThread(() -> {
                    positionPending = false;
                    if (isDestroyed() || preparing || transportCommandPending || tv != device
                            || generation != queueGeneration || revision != playbackRevision) return;
                    String before = playbackState.label();
                    playbackState.update(playback);
                    tvPlaying = playbackState.active();
                    sendButton.setEnabled(true);
                    sendButton.setText(tvPlaying ? "Controle da TV" : "Enviar à TV");
                    String label = playbackState.label();
                    DlnaController.Position position = playback == null ? null : playback.position;
                    if (tvPlaying && position != null && position.elapsedMs >= 0) {
                        lastTvElapsedMs = position.elapsedMs;
                        lastTvDurationMs = position.durationMs;
                        label += " · " + DlnaController.clock(lastTvElapsedMs)
                                + (lastTvDurationMs > 0 ? " / " + DlnaController.clock(lastTvDurationMs) : "");
                        if (localCastActive) getPreferences(Context.MODE_PRIVATE).edit()
                                .putLong("resumeTimeMs", lastTvElapsedMs).apply();
                    } else {
                        lastTvElapsedMs = -1; lastTvDurationMs = -1;
                    }
                    timeLabel.setText(label);
                    if (playbackState.terminal()) {
                        if (localCastActive) {
                            queueGeneration++;
                            if (mediaTask != null) mediaTask.cancel(true);
                        }
                        boolean wasCasting = localCastActive;
                        localCastActive = false;
                        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        releaseStreamingLocks();
                        if (wasCasting || !before.equals(playbackState.label()))
                            setStatus(playbackState.label() + ". Você pode enviar outro vídeo ou testar outra fonte.");
                    } else if (!before.equals(playbackState.label())) {
                        setStatus(playbackState.label());
                    }
                    // No automatic Play/Stop: a failed query is not proof playback stopped.
                }));
            }
            handler.postDelayed(this, 10000);
        }
    };
    private void refreshPlaybackSoon() {
        handler.removeCallbacks(pollTvPosition);
        handler.post(pollTvPosition);
    }
    private String pageUrl = "https://www.google.com/";
    private final Runnable pollVideos = new Runnable() {
        @Override public void run() {
            scanCurrentPage(false);
            handler.postDelayed(this, 4000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(SURFACE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        controller = new DlnaController(this);
        controller.setProgressListener(message -> runOnUiThread(() -> {
            if (!isDestroyed() && preparing) setStatus(message);
        }));
        proxy = new StreamProxy(this, false); // Preserve verified continuous MPEG-TS path.
        proxy.setProgressListener(message -> android.util.Log.d("VieraFlow", "Proxy: " + message));
        proxy.setErrorListener(error -> runOnUiThread(() -> {
            if (isDestroyed()) return;
            if (!localCastActive && !preparing) return;
            proxyStreamIssue = true;
            proxyIssueMessage = error;
            // The HTTP socket closing does not prove the TV has stopped decoding buffered media.
            setStatus("A conexão do vídeo apresentou falha. Consulte Diagnóstico.");
        }));
        buildUi();
        controller.discover(new DlnaController.DiscoveryListener() {
            @Override public void found(DlnaController.Device device) {
                runOnUiThread(() -> { if (!isDestroyed() && tv == null) selectTv(device); });
            }
            @Override public void finished(String error) { }
        });
        // Store only the local browsing session, never HLS segment URLs or CDN tokens.
        pageUrl = getPreferences(Context.MODE_PRIVATE).getString("lastPageUrl", pageUrl);
        if (state != null && state.getString("pageUrl") != null) pageUrl = state.getString("pageUrl");
        browser.loadUrl(pageUrl);
        handler.postDelayed(pollVideos, 2000);
        handler.postDelayed(pollTvPosition, 15000);
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private GradientDrawable surface(int color, int corner, boolean outlined) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(corner));
        if (outlined) g.setStroke(dp(1), LINE);
        return g;
    }
    private TextView text(String content, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(content); v.setTextColor(color); v.setTextSize(size);
        v.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }
    private LinearLayout vertical() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }
    private LinearLayout.LayoutParams margins(int width, int height, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
        p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p;
    }
    private TextView button(String label, boolean filled, View.OnClickListener click) {
        TextView b = text(label, 14, filled ? SURFACE : INK, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(surface(filled ? BLUE : SURFACE, 19, !filled));
        b.setOnClickListener(click);
        b.setClickable(true); b.setFocusable(true);
        return b;
    }
    private ImageView iconButton(int icon, String description, View.OnClickListener click) {
        ImageView view = new ImageView(this);
        view.setImageResource(icon);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setPadding(dp(13), dp(13), dp(13), dp(13));
        view.setContentDescription(description);
        view.setBackground(surface(SURFACE, 19, true));
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(click);
        return view;
    }

    private void buildUi() {
        LinearLayout root = vertical(); root.setBackgroundColor(BG);
        root.setPadding(dp(18), dp(4), dp(18), dp(10));
        setContentView(root);

        // Compact header: prioritize the web page, not a large decorative title.
        LinearLayout headline = new LinearLayout(this);
        headline.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text("VieraFlow", 19, INK, true);
        headline.addView(heading, new LinearLayout.LayoutParams(0, dp(33), 1));
        TextView menu = button("Opções", false, v -> showMainMenu());
        headline.addView(menu, margins(89, 34, 0, 0, 0, 0));
        root.addView(headline, margins(-1, 36, 4, 0, 0, 3));
        chromeHeadline = headline;

        LinearLayout connection = new LinearLayout(this);
        connection.setGravity(Gravity.CENTER_VERTICAL);
        connection.setBackground(surface(SURFACE, 26, true));
        connection.setPadding(dp(12), dp(5), dp(7), dp(5));
        LinearLayout deviceText = vertical();
        TextView small = text("SUA TV", 11, MUTED, true);
        deviceLabel = text("Nenhuma TV conectada", 15, INK, true);
        deviceLabel.setSingleLine(true); deviceLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        deviceLabel.setOnClickListener(v -> showTvOptions());
        deviceText.addView(small, margins(-1, 15, 0, 0, 0, 2));
        deviceText.addView(deviceLabel, margins(-1, 26, 0, 0, 0, 0));
        connection.addView(deviceText, new LinearLayout.LayoutParams(0, dp(42), 1));
        discoverButton = button("Buscar TV", false, v -> { if (tv == null) searchTv(); else showRemoteControl(); });
        connection.addView(discoverButton, margins(92, 42, 8, 0, 0, 0));
        root.addView(connection, margins(-1, 54, 0, 0, 0, 7));
        chromeConnection = connection;

        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setGravity(Gravity.CENTER_VERTICAL);
        urlRow.setBackground(surface(SURFACE, 23, true));
        address = new EditText(this);
        address.setSingleLine(true); address.setTextSize(14); address.setTextColor(INK);
        address.setHintTextColor(MUTED); address.setHint("Buscar ou abrir um site");
        address.setBackgroundColor(Color.TRANSPARENT);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setSelectAllOnFocus(true);
        address.setPadding(dp(15), 0, dp(8), 0);
        address.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                navigate(); return true;
            }
            return false;
        });
        urlRow.addView(address, new LinearLayout.LayoutParams(0, dp(50), 1));
        TextView go = button("Ir", true, v -> navigate());
        urlRow.addView(go, margins(66, 42, 0, 0, 5, 0));
        root.addView(urlRow, margins(-1, 56, 0, 0, 0, 7));

        LinearLayout tools = new LinearLayout(this); tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.addView(iconButton(R.drawable.ic_back, "Voltar", v -> { if (browserSuspended) restoreBrowser(); else if (browser.canGoBack()) browser.goBack(); }), margins(48, 48, 0, 0, 8, 0));
        tools.addView(iconButton(R.drawable.ic_refresh, "Atualizar", v -> { if (browserSuspended) restoreBrowser(); else browser.reload(); }), margins(48, 48, 0, 0, 8, 0));
        // One-tap browser expansion avoids squeezing desktop-oriented sites.
        expandButton = button("Expandir site", false, v -> toggleBrowserSize());
        tools.addView(expandButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        tools.addView(iconButton(R.drawable.ic_link, "Colar link direto", v -> manualUrl()), margins(48, 48, 8, 0, 0, 0));
        root.addView(tools, margins(-1, 48, 0, 0, 0, 7));

        FrameLayout webFrame = new FrameLayout(this);
        webFrame.setBackground(surface(SURFACE, 24, true));
        webFrame.setClipToOutline(true);
        browser = new WebView(this);
        browser.setBackgroundColor(SURFACE);
        browser.getSettings().setJavaScriptEnabled(true);
        browser.getSettings().setDomStorageEnabled(true);
        browser.getSettings().setMediaPlaybackRequiresUserGesture(true);
        browser.getSettings().setSupportZoom(true);
        browser.getSettings().setBuiltInZoomControls(true);
        browser.getSettings().setDisplayZoomControls(false);
        browser.getSettings().setUseWideViewPort(true);
        browser.getSettings().setLoadWithOverviewMode(true);
        browser.getSettings().setTextZoom(110);
        browser.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        browser.getSettings().setSupportMultipleWindows(true);
        // Sites sometimes try to spawn advertising windows. Never let them replace the page.
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean isDialog,
                    boolean isUserGesture, Message resultMsg) {
                if (!isDestroyed()) runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Janela adicional bloqueada", Toast.LENGTH_SHORT).show());
                return false;
            }
        });
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!suppressRedirects || browserSuspended || !request.isForMainFrame()) return false;
                String scheme = request.getUrl().getScheme();
                if (request.hasGesture() || scheme == null ||
                        !("http".equals(scheme) || "https".equals(scheme))) return false;
                // Allow redirect chains during initial navigation and same-site redirects.
                // After a page settles, block unsolicited cross-site top-frame navigations.
                if (System.currentTimeMillis() - pageLoadedAt < 6000) return false;
                Uri current = Uri.parse(pageUrl);
                String oldHost = current.getHost(), newHost = request.getUrl().getHost();
                if (oldHost == null || newHost == null || sameSite(oldHost, newHost)) return false;
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Redirecionamento inesperado bloqueado. Ajustes: Opções.", Toast.LENGTH_SHORT).show());
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (browserSuspended || !isWebUrl(url)) return;
                pageUrl = url; address.setText(url); address.setSelection(0); setCandidate(null);
                setStatus("Página carregando…");
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (browserSuspended || !isWebUrl(url)) return;
                pageUrl = url; address.setText(url); address.setSelection(0);
                pageLoadedAt = System.currentTimeMillis();
                setStatus("Abra o player do vídeo no site.");
                getPreferences(Context.MODE_PRIVATE).edit().putString("lastPageUrl", url).apply();
                handler.postDelayed(() -> scanCurrentPage(false), 800);
                if (pendingEpisodeLookup) {
                    pendingEpisodeLookup = false;
                    handler.postDelayed(() -> nextEpisode(), 700);
                }
                if (pendingManualScan) {
                    pendingManualScan = false;
                    handler.postDelayed(() -> scanCurrentPage(true), 700);
                }
            }
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String origin = null, accept = "";
                for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                    if ("referer".equalsIgnoreCase(header.getKey())) origin = header.getValue();
                    if ("accept".equalsIgnoreCase(header.getKey())) accept = header.getValue();
                }
                final String mediaReferer = origin;
                String path = request.getUrl().getPath();
                if (path == null) path = "";
                path = path.toLowerCase(Locale.ROOT);
                boolean segment = path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".aac") || path.endsWith(".key") || path.endsWith(".vtt");
                boolean mediaAccept = accept.contains("video/") || accept.contains("mpegurl");
                if (isMediaLink(url) || (mediaAccept && !segment)) runOnUiThread(() -> {
                    if (!browserSuspended && !preparing) {
                        setCandidate(url);
                        if (isWebUrl(mediaReferer)) detectedReferers.put(url, mediaReferer);
                    }
                });
                return null;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) setStatus("Falha ao abrir o site: " + error.getDescription());
            }
        });
        webFrame.addView(browser, new FrameLayout.LayoutParams(-1, -1));
        root.addView(webFrame, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = vertical();
        footer.setBackground(surface(SURFACE, 25, true));
        footer.setPadding(dp(13), dp(10), dp(13), dp(12));
        candidateLabel = text("Abra o player para detectar o vídeo", 13, MUTED, false);
        candidateLabel.setSingleLine(true); candidateLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        footer.addView(candidateLabel, margins(-1, 24, 3, 0, 0, 3));
        candidateLabel.setOnClickListener(v -> showSources());
        timeLabel = text("Na TV · sem reprodução", 12, MUTED, false);
        footer.addView(timeLabel, margins(-1, 18, 3, 0, 0, 3));
        LinearLayout controls = new LinearLayout(this);
        sendButton = button("Enviar à TV", true, v -> {
            if (tvPlaying) showPlayerControls(); else cast();
        });
        controls.addView(sendButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        controls.addView(iconButton(R.drawable.ic_pause, "Pausar na TV", v -> remote("Pause", "Pausado")), margins(48, 52, 8, 0, 0, 0));
        controls.addView(iconButton(R.drawable.ic_play, "Retomar na TV", v -> remote("Play", "Reproduzindo")), margins(48, 52, 8, 0, 0, 0));
        controls.addView(iconButton(R.drawable.ic_stop, "Parar na TV", v -> remote("Stop", "Parado")), margins(48, 52, 8, 0, 0, 0));
        footer.addView(controls, margins(-1, 52, 0, 0, 0, 0));
        root.addView(footer, margins(-1, 128, 0, 6, 0, 4));
        chromeFooter = footer;

        status = text("Procurando sua TV…", 12, MUTED, false);
        status.setSingleLine(true); status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setOnClickListener(v -> {
            String report = "VieraFlow 0.2.0-consolidado\n" + status.getText() + "\n\n"
                    + (proxyStreamIssue ? "Falha registrada: " + proxyIssueMessage + "\n\n" : "")
                    + proxy.streamDiagnostic() + "\n\n" + proxy.diagnostic()
                    + "\n\nOs tempos indicam dados enviados pelo servidor, não a posição exata exibida na TV.";
            new AlertDialog.Builder(this).setTitle("Diagnóstico da transmissão")
                    .setMessage(report)
                    .setNeutralButton("Copiar diagnóstico", (dialog, which) -> {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                                getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("VieraFlow diagnóstico", report));
                            android.widget.Toast.makeText(this, "Diagnóstico copiado", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setPositiveButton("Fechar", null).show();
        });
        root.addView(status, margins(-1, 18, 6, 0, 0, 0));
        chromeStatus = status;
    }

    private void toggleBrowserSize() {
        browserExpanded = !browserExpanded;
        int visible = browserExpanded ? View.GONE : View.VISIBLE;
        chromeHeadline.setVisibility(visible);
        chromeConnection.setVisibility(visible);
        chromeFooter.setVisibility(visible);
        chromeStatus.setVisibility(visible);
        expandButton.setText(browserExpanded ? "Mostrar controles" : "Expandir site");
    }

    /** Only check for host / subdomain equality. No generic ad network interception. */
    private static boolean sameSite(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT), y = b.toLowerCase(Locale.ROOT);
        return x.equals(y) || x.endsWith("." + y) || y.endsWith("." + x);
    }
    private void showMainMenu() {
        String[] options = {"Controle de reprodução", "Fontes detectadas", "Detectar vídeo novamente",
                "Abrir próximo episódio", "Continuar última página", "Ajustar navegador", "Diagnóstico"};
        new AlertDialog.Builder(this).setTitle("VieraFlow")
                .setItems(options, (dialog, option) -> {
                    if (option == 0) showPlayerControls();
                    else if (option == 1) showSources();
                    else if (option == 2) {
                        if (browserSuspended) { pendingManualScan = true; restoreBrowser(); }
                        else scanCurrentPage(true);
                    }
                    else if (option == 3) nextEpisode();
                    else if (option == 4) showRecentSession();
                    else if (option == 5) showBrowserAdjustments();
                    else status.performClick();
                }).show();
    }
    private void restoreBrowserIfNeeded() {
        if (browserSuspended) restoreBrowser();
    }
    private void showRecentSession() {
        android.content.SharedPreferences p = getPreferences(Context.MODE_PRIVATE);
        String url = p.getString("resumePage", p.getString("lastPageUrl", ""));
        if (!isWebUrl(url)) { setStatus("Ainda não há uma página salva."); return; }
        long ms = p.getLong("resumeTimeMs", -1);
        String name = p.getString("resumeTitle", "Último vídeo");
        String message = name + "\n\n" + url + (ms > 0 ? "\nÚltima posição indicada pela TV: "
                + DlnaController.clock(ms) : "")
                + "\n\nReabra a página e selecione o episódio. Não guardamos URLs temporárias dos servidores.";
        new AlertDialog.Builder(this).setTitle("Continuar assistindo").setMessage(message)
                .setNegativeButton("Cancelar", null).setPositiveButton("Abrir página", (d, which) -> {
                    browserSuspended = false; browser.loadUrl(url);
                }).show();
    }
    private void showSources() {
        if (preparing) { setStatus("Aguarde a verificação da fonte atual."); return; }
        if (detectedUrls.isEmpty() && candidateUrl == null) {
            setStatus("Nenhuma fonte ainda. Inicie o player e toque em Detectar novamente.");
            return;
        }
        List<String> urls = new ArrayList<>(detectedUrls);
        if (isWebUrl(candidateUrl) && !urls.contains(candidateUrl)) urls.add(candidateUrl);
        String[] labels = new String[urls.size()];
        for (int i = 0; i < urls.size(); i++) {
            String path = Uri.parse(urls.get(i)).getPath();
            String format = path != null && path.toLowerCase(Locale.ROOT).endsWith(".m3u8") ? "HLS" : "Vídeo";
            labels[i] = "Fonte " + (i + 1) + " · " + format + " · " + Uri.parse(urls.get(i)).getHost();
        }
        new AlertDialog.Builder(this).setTitle("Toque numa fonte para testar na TV")
                .setItems(labels, (dialog, index) -> {
                    String selected = urls.get(index);
                    candidateUrl = selected;
                    manualCandidate = true;
                    candidateLabel.setText(labels[index]);
                    if (tv != null) castUrl(selected);
                }).setNegativeButton("Cancelar", null).show();
    }
    private void showPlayerControls() {
        if (tv == null) { setStatus("Conecte a TV primeiro."); return; }
        LinearLayout panel = vertical(); panel.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView pos = text(lastTvElapsedMs >= 0
                ? "Posição informada pela TV: " + DlnaController.clock(lastTvElapsedMs)
                : "Aguardando posição da TV…", 14, INK, true);
        panel.addView(pos, margins(-1, 35, 0, 0, 0, 8));
        remoteRow(panel, new String[]{"−30 s", "Pausar", "+30 s"},
                new View.OnClickListener[]{v -> seek(-30000), v -> remote("Pause", "Pausado"), v -> seek(30000)});
        remoteRow(panel, new String[]{"Reproduzir", "Parar", "Reenviar"},
                new View.OnClickListener[]{v -> remote("Play", "Reproduzindo"),
                        v -> remote("Stop", "Parado"), v -> {
                    if (candidateUrl != null) cast();
                    else { restoreBrowserIfNeeded(); setStatus("Abra um vídeo para reenviar."); }
                }});
        new AlertDialog.Builder(this).setTitle("Controle de reprodução")
                .setView(panel)
                .setNeutralButton("Controle da TV", (d, w) -> showRemoteControl())
                .setPositiveButton("Fechar", null).show();
    }
    private void seek(long deltaMs) {
        if (tv == null || !tvPlaying) { setStatus("Inicie a reprodução na TV primeiro."); return; }
        controller.seekBy(tv, deltaMs, error -> runOnUiThread(() -> {
            if (isDestroyed()) return;
            if (error == null) setStatus("Comando de avanço enviado à TV.");
            else new AlertDialog.Builder(this).setTitle("Avanço indisponível")
                    .setMessage("Esta fonte ou a Panasonic pode não permitir avançar por DLNA."
                            + "\n\nDetalhe: " + error)
                    .setPositiveButton("OK", null).show();
        }));
    }
    private void nextEpisode() {
        if (browserSuspended) { pendingEpisodeLookup = true; restoreBrowser(); return; }
        browser.evaluateJavascript("(function(){let as=Array.from(document.querySelectorAll('a[href]'));"
                + "let a=as.find(x=>x.rel==='next'||/^(pr[oó]ximo( epis[oó]dio)?|next( episode)?)$/i.test((x.innerText||'').trim()));"
                + "return a&&/^https?:/.test(a.href)?a.href:null})()", value -> {
            String url = null;
            try { Object decoded = new JSONTokener(value).nextValue(); if (decoded instanceof String) url = (String) decoded; }
            catch (Exception ignored) { }
            if (!isWebUrl(url)) {
                setStatus("Não encontrei um botão 'Próximo episódio' nesta página. Navegue pelo site.");
                return;
            }
            final String destination = url;
            new AlertDialog.Builder(this).setTitle("Próximo episódio")
                    .setMessage("Abrir a próxima página sugerida pelo site?\n\n" + Uri.parse(url).getHost())
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Abrir", (d,w) -> { browserSuspended = false; browser.loadUrl(destination); })
                    .show();
        });
    }
    /** Site-level controls; the brightness change is app-only and never touches the TV. */
    private void showBrowserAdjustments() {
        LinearLayout panel = vertical();
        panel.setPadding(dp(20), dp(5), dp(20), dp(12));
        TextView label = text("Tamanho do site", 15, INK, true);
        panel.addView(label, margins(-1, 33, 0, 0, 0, 6));
        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView less = button("−", false, v -> browser.zoomOut());
        TextView more = button("+", false, v -> browser.zoomIn());
        TextView reset = button("Repor", false, v -> browser.getSettings().setTextZoom(110));
        zoomRow.addView(less, new LinearLayout.LayoutParams(0, dp(48), 1));
        zoomRow.addView(more, margins(70, 48, 8, 0, 0, 0));
        zoomRow.addView(reset, margins(88, 48, 8, 0, 0, 0));
        panel.addView(zoomRow);
        TextView brightness = text("Brilho do celular (não altera o brilho da TV)", 14, INK, true);
        panel.addView(brightness, margins(-1, 40, 0, 12, 0, 0));
        SeekBar brightnessSlider = new SeekBar(this);
        float current = getWindow().getAttributes().screenBrightness;
        brightnessSlider.setProgress(Math.round((current < 0 ? 0.65f : current) * 100f));
        brightnessSlider.setMax(100);
        brightnessSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seek, int value, boolean fromUser) {
                if (!fromUser) return;
                android.view.WindowManager.LayoutParams params = getWindow().getAttributes();
                params.screenBrightness = Math.max(0.02f, value / 100f);
                getWindow().setAttributes(params);
            }
            @Override public void onStartTrackingTouch(SeekBar seek) { }
            @Override public void onStopTrackingTouch(SeekBar seek) { }
        });
        panel.addView(brightnessSlider, margins(-1, 45, 0, 0, 0, 0));
        android.widget.CheckBox redirectCheck = new android.widget.CheckBox(this);
        redirectCheck.setText("Bloquear redirecionamentos automáticos entre sites");
        redirectCheck.setChecked(suppressRedirects);
        redirectCheck.setTextSize(13);
        redirectCheck.setOnCheckedChangeListener((button, checked) -> suppressRedirects = checked);
        panel.addView(redirectCheck, margins(-1, 56, 0, 5, 0, 0));
        new AlertDialog.Builder(this)
                .setTitle("Ajustar navegação")
                .setView(panel)
                .setNeutralButton("Detectar novamente", (dialog, which) -> scanCurrentPage(true))
                .setNegativeButton("Brilho padrão", (dialog, which) -> {
                    android.view.WindowManager.LayoutParams params = getWindow().getAttributes();
                    params.screenBrightness = -1;
                    getWindow().setAttributes(params);
                })
                .setPositiveButton("Fechar", null)
                .show();
    }

    private void setStatus(String message) { status.setText(message == null ? "" : message); }
    private static boolean isWebUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }
    private static boolean isMediaLink(String url) {
        String path = url.split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
        return (path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".mov"))
                && (url.startsWith("http://") || url.startsWith("https://"));
    }
    private void setCandidate(String url) {
        if (url == null) {
            candidateUrl = null; detectedUrls.clear(); detectedReferers.clear(); manualCandidate = false;
            candidateLabel.setText("Nenhum vídeo identificado"); return;
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) return;
        manualCandidate = false;
        if (!detectedUrls.contains(url)) {
            if (detectedUrls.size() >= 12) detectedReferers.remove(detectedUrls.remove(0));
            detectedUrls.add(url);
        }
        candidateUrl = url;
        String ext = url.split("[?#]", 2)[0].toLowerCase(Locale.ROOT).endsWith(".m3u8") ? "HLS" : "Vídeo";
        candidateLabel.setText("● " + ext + " identificado" + (detectedUrls.size() > 1 ? " · " + detectedUrls.size() + " fontes" : "") + " — pronto para enviar");
    }

    private void navigate() {
        try {
            String url = Navigation.target(address.getText().toString());
            if (url == null) return;
            browserSuspended = false;
            hideKeyboard(); browser.loadUrl(url);
        } catch (IllegalArgumentException e) { setStatus(e.getMessage()); }
    }
    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(address.getWindowToken(), 0);
        address.clearFocus();
    }
    private void scanCurrentPage(boolean showToast) {
        if (browser == null || browserSuspended || preparing) return;
        String script = "(function(){let out=[];function add(u){if(u&&/^https?:/i.test(u)&&!out.includes(u))out.push(u)}"
                + "function scan(d,depth){if(depth>8)return;for(let e of d.querySelectorAll('video,video source')){add(e.currentSrc);add(e.src)}"
                + "for(let a of d.querySelectorAll('a[href]')){if(/\\.(mp4|m3u8|mov)([?#]|$)/i.test(a.href))add(a.href)}"
                + "for(let f of d.querySelectorAll('iframe')){try{if(f.contentDocument)scan(f.contentDocument,depth+1)}catch(e){}}}"
                + "scan(document,0);return out.slice(0,12)})()";
        browser.evaluateJavascript(script, value -> {
            if (browserSuspended || preparing) return;
            try {
                Object obj = new JSONTokener(value).nextValue();
                if (obj instanceof JSONArray) {
                    JSONArray found = (JSONArray) obj;
                    for (int i = 0; i < found.length(); i++) setCandidate(found.optString(i));
                }
                if (candidateUrl != null) {
                    if (showToast) setStatus("Vídeo encontrado. Toque em Enviar à TV.");
                } else if (showToast && candidateUrl == null) {
                    setStatus("Vídeo não detectado. Abra e inicie o player, ou use Link direto.");
                }
            } catch (Exception ignored) { if (showToast) setStatus("Não consegui ler o player desta página."); }
        });
    }
    private void manualUrl() {
        EditText entry = new EditText(this);
        entry.setSingleLine(true); entry.setTextSize(14);
        entry.setHint("https://…/filme.mp4 ou …/playlist.m3u8");
        entry.setPadding(dp(16), dp(16), dp(16), dp(16));
        new AlertDialog.Builder(this).setTitle("Link de vídeo").setMessage("Cole a URL direta de um arquivo de vídeo ou playlist.")
                .setView(entry).setNegativeButton("Cancelar", null)
                .setPositiveButton("Usar link", (dialog, which) -> {
                    String url = entry.getText().toString().trim();
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        detectedUrls.clear(); candidateUrl = url; manualCandidate = true;
                        candidateLabel.setText("● Link de vídeo informado");
                    } else setStatus("Informe uma URL HTTP/HTTPS válida.");
                }).show();
    }

    private void searchTv() {
        if (discovering) {
            Toast.makeText(this, "A busca já está em andamento", Toast.LENGTH_SHORT).show();
            return;
        }
        discovering = true; tvs.clear();
        discoverButton.setText("Buscando…");
        discoverButton.setEnabled(false);
        setStatus("Procurando TVs DLNA na rede…");
        Toast.makeText(this, "Iniciando busca de TVs na rede", Toast.LENGTH_SHORT).show();
        LinearLayout progress = new LinearLayout(this);
        progress.setGravity(Gravity.CENTER_VERTICAL);
        progress.setPadding(dp(22), dp(14), dp(22), dp(14));
        ProgressBar spinner = new ProgressBar(this);
        progress.addView(spinner, margins(35, 35, 0, 0, 18, 0));
        TextView progressText = text("Buscando receptores DLNA…\nAguarde alguns segundos.", 14, INK, false);
        progress.addView(progressText, new LinearLayout.LayoutParams(-1, dp(55)));
        discoveryDialog = new AlertDialog.Builder(this).setTitle("Localizando sua Panasonic")
                .setView(progress).setCancelable(false).create();
        discoveryDialog.show();
        controller.discover(new DlnaController.DiscoveryListener() {
            @Override public void found(DlnaController.Device device) {
                runOnUiThread(() -> {
                    for (DlnaController.Device existing : tvs) if (existing.controlUrl.equals(device.controlUrl)) return;
                    tvs.add(device); setStatus("TV encontrada: " + device.name);
                    progressText.setText("Encontrada: " + device.name);
                });
            }
            @Override public void finished(String error) {
                runOnUiThread(() -> {
                    discovering = false;
                    discoverButton.setText(tv == null ? "Buscar TV" : "Controle");
                    discoverButton.setEnabled(true);
                    if (discoveryDialog != null) discoveryDialog.dismiss();
                    discoveryDialog = null;
                    if (tvs.isEmpty()) {
                        String reason = error == null ? "Nenhum receptor DLNA encontrado." : error;
                        setStatus(reason);
                        new AlertDialog.Builder(MainActivity.this).setTitle("Não encontrei a TV")
                                .setMessage(reason + "\n\nA TV e o celular devem estar na mesma rede local, sem isolamento de dispositivos. A função TV Remote não garante que o receptor DLNA esteja ativo.")
                                .setNegativeButton("Fechar", null)
                                .setNeutralButton("Usar IP da TV", (dialog, which) -> connectByIp())
                                .setPositiveButton("Tentar de novo", (dialog, which) -> searchTv()).show();
                    } else if (tvs.size() == 1) {
                        selectTv(tvs.get(0));
                        setStatus("TV pronta. Abra um vídeo e toque em Enviar à TV.");
                    }
                    else pickTv();
                });
            }
        });
    }
    private void showNetworkHelp() {
        String phoneIp = "indisponível";
        try {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && wifi.getConnectionInfo() != null) {
                int ip = wifi.getConnectionInfo().getIpAddress();
                if (ip != 0) phoneIp = (ip & 255) + "." + ((ip >> 8) & 255) + "." + ((ip >> 16) & 255) + "." + ((ip >> 24) & 255);
            }
        } catch (Exception ignored) { }
        new AlertDialog.Builder(this).setTitle("Diagnóstico de conexão")
                .setMessage("IP do celular: " + phoneIp + "\n\nConfira o IP da TV em MENU → Rede → Status da rede. Se eles estiverem em redes diferentes ou houver isolamento Wi-Fi, a descoberta não funcionará.\n\nAnote os dois IPs e a mensagem da busca para me enviar.")
                .setPositiveButton("Fechar", null).show();
    }
    private static boolean isValidIpv4(String value) {
        if (!value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return false;
        for (String number : value.split("\\.")) if (Integer.parseInt(number) > 255) return false;
        return true;
    }
    private void connectByIp() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(12), dp(16), dp(12), dp(12));
        EditText[] octets = new EditText[4];
        String saved = getSharedPreferences("viera", MODE_PRIVATE).getString("tvIp", "192.168.1.21");
        String[] previous = saved.split("\\.");
        for (int i = 0; i < 4; i++) {
            final int index = i;
            EditText field = new EditText(this);
            field.setSingleLine(true);
            field.setTextSize(20);
            field.setTextColor(INK);
            field.setGravity(Gravity.CENTER);
            field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
            field.setImeOptions(i == 3 ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
            field.setBackground(surface(BG, 15, true));
            field.setSelectAllOnFocus(true);
            if (previous.length == 4) field.setText(previous[i]);
            row.addView(field, new LinearLayout.LayoutParams(0, dp(58), 1));
            octets[i] = field;
            if (i < 3) {
                TextView dot = text(".", 22, MUTED, true);
                dot.setGravity(Gravity.CENTER);
                row.addView(dot, margins(18, 58, 0, 0, 0, 0));
            }
            field.setOnEditorActionListener((v, actionId, event) -> {
                if (index < 3 && (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE)) {
                    octets[index + 1].requestFocus(); return true;
                }
                return false;
            });
            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable value) {
                    if (index < 3 && field.hasFocus() && value.length() == 3) {
                        try { if (Integer.parseInt(value.toString()) <= 255) octets[index + 1].requestFocus(); }
                        catch (NumberFormatException ignored) { }
                    }
                }
            });
        }
        new AlertDialog.Builder(this).setTitle("Conectar Panasonic por IP")
                .setMessage("Digite só os números. Os pontos já estão prontos.")
                .setView(row).setNegativeButton("Cancelar", null)
                .setPositiveButton("Conectar", (dialog, which) -> {
                    String ip = octets[0].getText().toString().trim() + "."
                            + octets[1].getText().toString().trim() + "."
                            + octets[2].getText().toString().trim() + "."
                            + octets[3].getText().toString().trim();
                    if (!isValidIpv4(ip)) {
                        new AlertDialog.Builder(this).setTitle("IP inválido")
                                .setMessage("Use somente quatro números separados por pontos, como 192.168.1.100.")
                                .setPositiveButton("Tentar novamente", (d, w) -> connectByIp()).show();
                        return;
                    }
                    setStatus("Tentando conectar diretamente à Panasonic…");
                    ProgressBar spinner = new ProgressBar(this);
                    LinearLayout box = vertical();
                    box.setGravity(Gravity.CENTER); box.setPadding(dp(20), dp(15), dp(20), dp(15));
                    box.addView(spinner, margins(36, 36, 0, 0, 0, 0));
                    AlertDialog wait = new AlertDialog.Builder(this).setTitle("Conectando a " + ip)
                            .setView(box).setCancelable(false).create();
                    wait.show();
                    controller.connectPanasonicByIp(ip, (device, error) -> runOnUiThread(() -> {
                        wait.dismiss();
                        if (device != null) {
                            getSharedPreferences("viera", MODE_PRIVATE).edit().putString("tvIp", ip).apply();
                            selectTv(device);
                            setStatus("TV conectada. Abra um vídeo e toque em Enviar à TV.");
                        } else {
                            setStatus(error);
                            new AlertDialog.Builder(this).setTitle("Não conectou pelo IP")
                                    .setMessage(error + "\n\nConfirme o IP na TV e tente novamente. Também é possível abrir Diagnóstico para comparar as redes.")
                                    .setNegativeButton("Fechar", null)
                                    .setNeutralButton("Diagnóstico", (d, w) -> showNetworkHelp())
                                    .setPositiveButton("Tentar novamente", (d, w) -> connectByIp()).show();
                        }
                    }));
                }).show();
    }
    private void pickTv() {
        String[] options = new String[tvs.size()];
        for (int i = 0; i < tvs.size(); i++) options[i] = tvs.get(i).name;
        new AlertDialog.Builder(this).setTitle("Escolha sua TV")
                .setItems(options, (dialog, which) -> selectTv(tvs.get(which))).show();
    }
    private void selectTv(DlnaController.Device device) {
        playbackRevision++;
        tvPlaying = false; playbackState.reset();
        tv = device; deviceLabel.setText(device.name);
        refreshPlaybackSoon();
        discoverButton.setText("Controle");
        setStatus("Conectada: " + device.name + " (DLNA/DMR)");
    }
    private void remoteFeedback(String error) {
        if (isDestroyed() || error == null) return;
        setStatus("Controle: " + error);
        Toast.makeText(this, error, Toast.LENGTH_LONG).show();
    }
    private void key(String value) {
        if (tv == null) return;
        controller.remoteKey(tv, value, error -> runOnUiThread(() -> remoteFeedback(error)));
    }
    private void volume(int delta) {
        if (tv == null) return;
        controller.adjustVolume(tv, delta, error -> runOnUiThread(() -> remoteFeedback(error)));
    }
    private void mute() {
        if (tv == null) return;
        controller.toggleMute(tv, error -> runOnUiThread(() -> remoteFeedback(error)));
    }
    private void remoteRow(LinearLayout panel, String[] labels, View.OnClickListener[] actions) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        for (int i = 0; i < labels.length; i++) {
            View control = labels[i].isEmpty() ? new View(this) : button(labels[i], labels[i].equals("OK"), actions[i]);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(49), 1);
            size.setMargins(dp(3), dp(3), dp(3), dp(3));
            row.addView(control, size);
        }
        panel.addView(row, new LinearLayout.LayoutParams(-1, dp(55)));
    }
    private void showRemoteControl() {
        if (tv == null) { searchTv(); return; }
        LinearLayout panel = vertical();
        panel.setPadding(dp(14), dp(5), dp(14), dp(8));
        remoteRow(panel, new String[]{"Vol −", "Mudo", "Vol +"}, new View.OnClickListener[]{
                v -> volume(-2), v -> mute(), v -> volume(2)});
        remoteRow(panel, new String[]{"", "↑", ""}, new View.OnClickListener[]{
                v -> { }, v -> key("NRC_UP-ONOFF"), v -> { }});
        remoteRow(panel, new String[]{"←", "OK", "→"}, new View.OnClickListener[]{
                v -> key("NRC_LEFT-ONOFF"), v -> key("NRC_ENTER-ONOFF"), v -> key("NRC_RIGHT-ONOFF")});
        remoteRow(panel, new String[]{"", "↓", ""}, new View.OnClickListener[]{
                v -> { }, v -> key("NRC_DOWN-ONOFF"), v -> { }});
        remoteRow(panel, new String[]{"Voltar", "Início", "Menu"}, new View.OnClickListener[]{
                v -> key("NRC_RETURN-ONOFF"), v -> key("NRC_HOME-ONOFF"), v -> key("NRC_MENU-ONOFF")});
        remoteRow(panel, new String[]{"CH −", "Entrada", "CH +"}, new View.OnClickListener[]{
                v -> key("NRC_CH_DOWN-ONOFF"), v -> key("NRC_CHG_INPUT-ONOFF"), v -> key("NRC_CH_UP-ONOFF")});
        new AlertDialog.Builder(this).setTitle("Controle · " + tv.name).setView(panel)
                .setNeutralButton("Player", (d, w) -> showPlayerControls())
                .setPositiveButton("Fechar", null).show();
    }
    private void showTvOptions() {
        new AlertDialog.Builder(this).setTitle(tv == null ? "Conectar TV" : tv.name)
                .setItems(new String[]{"Buscar TV", "Conectar por IP", "Testar vídeo público", "Diagnóstico de rede", "Como funciona"}, (dialog, index) -> {
                    if (index == 0) searchTv();
                    else if (index == 1) connectByIp();
                    else if (index == 2) {
                        if (tv == null) { setStatus("Conecte a TV para testar."); searchTv(); }
                        else castUrl("https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyrides.mp4");
                    } else if (index == 3) showNetworkHelp();
                    else new AlertDialog.Builder(this).setTitle("Sem espelhamento")
                            .setMessage("Este app envia o endereço do vídeo ao receptor DLNA da Panasonic. O celular apenas controla a reprodução e encaminha dados temporariamente. Vídeos protegidos por DRM, codecs incompatíveis e players em blob não são suportados.")
                            .setPositiveButton("Entendi", null).show();
                }).show();
    }
    private void cast() {
        if (tv == null) { setStatus("Busque e selecione sua TV primeiro."); searchTv(); return; }
        if (candidateUrl == null) { scanCurrentPage(true); setStatus("Inicie o vídeo no site; depois tente Enviar à TV."); return; }
        castUrl(candidateUrl, !manualCandidate, new ArrayList<>(detectedUrls));
    }
    private void castUrl(String url) {
        castUrl(url, false, new ArrayList<>());
    }
    private String chooseSource(String fallback, List<String> sources, Map<String, String> referers, String page, String agent) throws Exception {
        String best = null; long bestScore = Long.MIN_VALUE, shortest = Long.MAX_VALUE;
        if (!sources.contains(fallback)) sources.add(fallback);
        for (int i = sources.size() - 1, checked = 0; i >= 0 && checked < 12; i--, checked++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Envio cancelado.");
            String source = sources.get(i);
            try {
                proxy.start(referers.containsKey(source) ? referers.get(source) : page, agent);
                StreamProxy.SourceInfo info = proxy.inspect(source);
                long score;
                if (info.durationMs >= 60000) score = 1000000000000L + Math.min(info.durationMs, 10000000000L);
                else if (info.hls) score = 900000000000L;
                else if (info.bytes >= 20L * 1024 * 1024) score = 800000000000L + Math.min(info.bytes, 10000000000L);
                else if (info.durationMs > 0) {
                    shortest = Math.min(shortest, info.durationMs);
                    score = info.durationMs;
                } else score = 500000000000L + Math.max(0, info.bytes);
                if (score > bestScore) { bestScore = score; best = source; }
            } catch (Exception ignored) {
                if (best == null) { best = source; bestScore = 100000000000L; }
            }
        }
        if (bestScore < 60000 && shortest != Long.MAX_VALUE)
            throw new java.io.IOException("O site só entregou um clipe de " + Math.max(1, shortest / 1000)
                    + " segundos. Aguarde o filme começar no navegador e toque em Detectar vídeo novamente.");
        return best == null ? fallback : best;
    }
    private void castUrl(String url, boolean autoSelect, List<String> sources) {
        if (preparing || transportCommandPending || tv == null) return;
        final int generation = ++queueGeneration;
        if (mediaTask != null) mediaTask.cancel(true);
        final DlnaController.Device destination = tv;
        final String title = browser.getTitle() == null ? "Vídeo" : browser.getTitle();
        castingTitle = title;
        castingPage = pageUrl;
        tvPlaying = false; proxyStreamIssue = false; proxyIssueMessage = "";
        lastTvElapsedMs = -1; lastTvDurationMs = -1;
        final String page = pageUrl, agent = browser.getSettings().getUserAgentString();
        final Map<String, String> referers = new HashMap<>(detectedReferers);
        try {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            holdStreamingLocks();
            preparing = true; sendButton.setEnabled(false); sendButton.setText("Verificando…");
            setStatus("Verificando vídeo antes de enviar para " + destination.name + "…");
            mediaTask = mediaWorker.submit(() -> {
                try {
                    String chosen = autoSelect ? chooseSource(url, sources, referers, page, agent) : url;
                    if (generation != queueGeneration || Thread.currentThread().isInterrupted()) return;
                    proxy.start(referers.containsKey(chosen) ? referers.get(chosen) : page, agent);
                    StreamProxy.Media media = proxy.prepare(chosen);
                    if (generation != queueGeneration || Thread.currentThread().isInterrupted()) return;
                    proxy.resetDiagnostic();
                    runOnUiThread(() -> {
                        if (isDestroyed() || generation != queueGeneration) return;
                        // Keep the page available when a short clip is detected. Unload it only after selecting the real source.
                        browserSuspended = true;
                        browser.stopLoading();
                        browser.loadDataWithBaseURL("about:blank", "<html><meta name='viewport' content='width=device-width'><body style='font-family:sans-serif;display:grid;place-items:center;height:90vh;color:#181c23;background:#f6f7fb'><div style='text-align:center'><div style='font-size:64px'>▣</div><h2>Enviando para a TV</h2></div></body></html>", "text/html", "UTF-8", null);
                        setStatus("Vídeo verificado. Aguardando a TV iniciar…");
                    });
                    controller.play(destination, media.url, title, media.mime, () -> generation == queueGeneration,
                            error -> runOnUiThread(() -> {
                                if (generation != queueGeneration || isDestroyed()) return;
                                finishCast(error);
                                if (error == null && proxy.hasMoreHls() && generation == queueGeneration)
                                    mediaTask = mediaWorker.submit(() -> playRemainingHls(destination, title, media, generation));
                            }));
                } catch (Exception e) {
                    runOnUiThread(() -> { if (generation == queueGeneration) finishCast("Fonte de vídeo: " + e.getMessage()); });
                }
            });
        } catch (Exception e) { finishCast("Transmissão indisponível: " + e.getMessage()); }
    }
    /** Sleeps until the chunk has been playing for targetMs (returns at once when targetMs is already past). */
    private void sleepUntilPlayed(long sinceNanos, long targetMs, int generation) throws InterruptedException {
        while (generation == queueGeneration) {
            long remaining = targetMs - (System.nanoTime() - sinceNanos) / 1000000L;
            if (remaining <= 0) return;
            Thread.sleep(Math.min(remaining, 1000));
        }
    }
    private void playRemainingHls(DlnaController.Device destination, String title, StreamProxy.Media playing, int generation) {
        int part = 2;
        // Downloading and remuxing the next chunk must not steal CPU from the threads that feed the TV.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND
                + android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE);
        long playingSince = System.nanoTime(); // the TV confirmed PLAYING just before this task started
        try {
            while (generation == queueGeneration && !Thread.currentThread().isInterrupted() && proxy.hasMoreHls()) {
                final int partNumber = part;
                // Prefetch late: the phone relays the video over the same Wi-Fi it downloads from, so preparing
                // the next chunk during the whole current one competes with the stream to the TV. Start only
                // as early as the measured preparation speed requires (with a safety margin).
                long nextMs = proxy.nextHlsDurationMs();
                double perMs = playing.durationMs > 0 && playing.prepareMs > 0
                        ? (double) playing.prepareMs / playing.durationMs : 0.25;
                long leadMs = Math.max(45000L, (long) (perMs * nextMs * 1.6) + 20000L);
                sleepUntilPlayed(playingSince, playing.durationMs - leadMs, generation);
                if (generation != queueGeneration || Thread.currentThread().isInterrupted()) return;
                runOnUiThread(() -> { if (!isDestroyed()) setStatus("Preparando próximo trecho enquanto a TV reproduz…"); });
                StreamProxy.Media next = proxy.prepareNextHls();
                if (next == null) break;
                runOnUiThread(() -> { if (!isDestroyed())
                    setStatus("Trecho " + partNumber + " pronto em " + (next.prepareMs / 1000) + " s. A troca ocorre ao fim deste."); });
                // Ask the TV for its state only near the end of the chunk: frequent SOAP calls load an old TV.
                sleepUntilPlayed(playingSince, playing.durationMs - 8000L, generation);
                int errors = 0;
                while (generation == queueGeneration && !Thread.currentThread().isInterrupted()) {
                    try {
                        String state = controller.transportState(destination);
                        if ("STOPPED".equals(state) || "NO_MEDIA_PRESENT".equals(state)) break;
                        errors = 0;
                    } catch (Exception e) {
                        if (++errors >= 3) throw e;
                    }
                    Thread.sleep(1000);
                }
                if (generation != queueGeneration || Thread.currentThread().isInterrupted()) return;
                CountDownLatch started = new CountDownLatch(1);
                AtomicReference<String> result = new AtomicReference<>();
                controller.play(destination, next.url, title, next.mime, () -> generation == queueGeneration,
                        error -> { result.set(error); started.countDown(); });
                if (!started.await(45, TimeUnit.SECONDS)) throw new java.io.IOException("A TV não respondeu ao próximo trecho.");
                if (result.get() != null) throw new java.io.IOException(result.get());
                proxy.discardExcept(next.url);
                playing = next; playingSince = System.nanoTime();
                holdStreamingLocks();
                runOnUiThread(() -> { if (!isDestroyed()) setStatus("Trecho " + partNumber + " reproduzindo na TV."); });
                part++;
            }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        catch (Exception error) {
            runOnUiThread(() -> {
                if (!isDestroyed() && generation == queueGeneration)
                    setStatus("A reprodução parou: " + error.getMessage());
            });
        } finally {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
        }
    }
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private android.os.PowerManager.WakeLock cpuLock;
    /** Keeps the Wi-Fi radio out of power-save and the CPU awake while the phone relays video to the TV. */
    private synchronized void holdStreamingLocks() {
        try {
            if (wifiLock == null) {
                android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                wifiLock = wifi.createWifiLock(android.os.Build.VERSION.SDK_INT >= 29
                        ? android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VieraFlow:stream");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
            if (cpuLock == null) {
                android.os.PowerManager power = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                cpuLock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "VieraFlow:stream");
                cpuLock.setReferenceCounted(false);
            }
            cpuLock.acquire(2 * 60 * 60 * 1000L); // renewed at each chunk; expires by itself if the app is lost
        } catch (Exception e) { android.util.Log.w("VieraFlow", "Locks", e); }
    }
    private synchronized void releaseStreamingLocks() {
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        try { if (cpuLock != null && cpuLock.isHeld()) cpuLock.release(); } catch (Exception ignored) { }
    }
    private void finishCast(String error) {
        if (isDestroyed()) return;
        preparing = false; sendButton.setEnabled(true); sendButton.setText("Enviar à TV");
        if (error == null) {
            tvPlaying = true; localCastActive = true;
            sendButton.setText("Controle da TV");
            refreshPlaybackSoon();
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getPreferences(Context.MODE_PRIVATE).edit().putString("resumePage", castingPage)
                    .putString("resumeTitle", castingTitle).putLong("resumeTimeMs", 0).apply();
            timeLabel.setText("Na TV · reproduzindo");
            setStatus("Reproduzindo na TV. Toque em Opções para avançar ou retomar.");
        } else {
            tvPlaying = false;
            if (error.contains("código 716")) error += "\n\nDiagnóstico: " + proxy.diagnostic();
            if (!localCastActive) {
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                releaseStreamingLocks();
            }
            setStatus(error);
            refreshPlaybackSoon();
            new AlertDialog.Builder(this).setTitle("Não foi possível reproduzir")
                    .setMessage(error).setPositiveButton("OK", null).show();
        }
    }
    private void restoreBrowser() {
        browserSuspended = false;
        browser.loadUrl(pageUrl);
    }
    private void remote(String action, String success) {
        if (tv == null) { setStatus("Conecte a TV primeiro."); return; }
        if (transportCommandPending || (preparing && !"Stop".equals(action))) {
            setStatus("Aguarde o comando atual da TV."); return;
        }
        final DlnaController.Device destination = tv;
        final int revision = ++playbackRevision;
        transportCommandPending = true;
        if ("Stop".equals(action)) {
            preparing = false; sendButton.setEnabled(true);
            sendButton.setText("Consultar TV…");
            queueGeneration++;
            if (mediaTask != null) mediaTask.cancel(true);
        }
        controller.command(destination, action, error -> runOnUiThread(() -> {
            transportCommandPending = false;
            if (isDestroyed() || tv != destination || revision != playbackRevision) return;
            setStatus(error == null ? "Comando enviado. Consultando estado da TV…" : "Falha no controle: " + error);
            refreshPlaybackSoon();
        }));
    }

    @Override public void onBackPressed() {
        if (browserSuspended) restoreBrowser(); else if (browser.canGoBack()) browser.goBack(); else super.onBackPressed();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("pageUrl", pageUrl); super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy() {
        queueGeneration++;
        if (discoveryDialog != null) discoveryDialog.dismiss();
        handler.removeCallbacks(pollVideos);
        handler.removeCallbacks(pollTvPosition);
        mediaWorker.shutdownNow();
        if (browser != null) { browser.stopLoading(); browser.destroy(); }
        releaseStreamingLocks();
        proxy.close(); controller.close(); super.onDestroy();
    }
}
