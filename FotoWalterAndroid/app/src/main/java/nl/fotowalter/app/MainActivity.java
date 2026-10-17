package nl.fotowalter.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.graphics.Typeface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final String PHOTO_URL = "https://fotowalter.nl/";
    private static final String RADIO_URL = "https://radioschipluiden.nl/";
    private static final String STREAM_URL = "https://c40.radioboss.fm:8298/stream";
    private static final String[] TITLES = {
            "https://c40.radioboss.fm:8298/7.html",
            "https://c40.radioboss.fm:8298/status-json.xsl"
    };
    private static final int INK = Color.rgb(13, 18, 27);
    private static final int WHITE = Color.WHITE;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout screen;
    private FrameLayout browserArea;
    private WebView web;
    private View overlay;
    private TextView nowPlaying;
    private TextView stateLabel;
    private Button playButton;
    private Button siteButton;
    private MediaPlayer player;
    private boolean preparing;
    private boolean playing;
    private boolean offline;
    private boolean fetching;
    private boolean destroyed;
    private boolean showingRadio;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateTrack();
            handler.postDelayed(this, 18000);
        }
    };

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(INK);
        getWindow().setNavigationBarColor(INK);

        screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        browserArea = new FrameLayout(this);
        screen.addView(browserArea, new LinearLayout.LayoutParams(-1, 0, 1));

        web = new WebView(this);
        web.setBackgroundColor(WHITE);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMediaPlaybackRequiresUserGesture(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (!req.isForMainFrame()) return false;
                Uri uri = req.getUrl();
                String host = uri.getHost();
                if ("https".equalsIgnoreCase(uri.getScheme()) && host != null &&
                    (host.equals("fotowalter.nl") || host.equals("www.fotowalter.nl") ||
                     host.equals("radioschipluiden.nl") || host.equals("www.radioschipluiden.nl"))) {
                    return false;
                }
                openOther(uri);
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (destroyed) return;
                offline = false;
                if (overlay != null) {
                    browserArea.removeView(overlay);
                    overlay = null;
                }
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError error) {
                if (req.isForMainFrame()) showOffline();
            }
        });
        web.setDownloadListener((url, agent, disposition, mime, length) -> {
            try { openOther(Uri.parse(url)); } catch (Exception ignored) {}
        });
        browserArea.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setupRadioBar();
        setContentView(screen);
        showLoading();
        if (state == null || web.restoreState(state) == null) web.loadUrl(PHOTO_URL);
        handler.post(refresh);
    }

    private void setupRadioBar() {
        LinearLayout playerBar = new LinearLayout(this);
        playerBar.setOrientation(LinearLayout.VERTICAL);
        playerBar.setPadding(dp(14), dp(9), dp(14), dp(9));
        playerBar.setBackgroundColor(INK);
        screen.addView(playerBar, new LinearLayout.LayoutParams(-1, -2));

        TextView brand = new TextView(this);
        brand.setText("●  RADIO SCHIPLUIDEN  ·  LIVE");
        brand.setTextColor(Color.rgb(250, 116, 100));
        brand.setTextSize(12);
        brand.setTypeface(null, Typeface.BOLD);
        playerBar.addView(brand);

        LinearLayout trackRow = new LinearLayout(this);
        trackRow.setOrientation(LinearLayout.HORIZONTAL);
        trackRow.setGravity(Gravity.CENTER_VERTICAL);
        playerBar.addView(trackRow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout trackColumn = new LinearLayout(this);
        trackColumn.setOrientation(LinearLayout.VERTICAL);
        trackRow.addView(trackColumn, new LinearLayout.LayoutParams(0, -2, 1));

        nowPlaying = new TextView(this);
        nowPlaying.setText("Huidig nummer ophalen...");
        nowPlaying.setTextColor(WHITE);
        nowPlaying.setTextSize(15);
        nowPlaying.setTypeface(null, Typeface.BOLD);
        nowPlaying.setSingleLine(true);
        nowPlaying.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        nowPlaying.setMarqueeRepeatLimit(-1);
        nowPlaying.setSelected(true);
        trackColumn.addView(nowPlaying);

        stateLabel = new TextView(this);
        stateLabel.setText("Tik op afspelen om live te luisteren");
        stateLabel.setTextColor(Color.rgb(187, 192, 204));
        stateLabel.setTextSize(12);
        trackColumn.addView(stateLabel);

        playButton = new Button(this);
        playButton.setAllCaps(false);
        playButton.setText("▶");
        playButton.setContentDescription("Start de radio");
        playButton.setTextSize(20);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(dp(66), dp(51));
        b.leftMargin = dp(8);
        trackRow.addView(playButton, b);
        playButton.setOnClickListener(v -> toggleRadio());

        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        playerBar.addView(navRow, new LinearLayout.LayoutParams(-1, -2));

        Button newsButton = new Button(this);
        newsButton.setAllCaps(false);
        newsButton.setText("112-nieuws");
        navRow.addView(newsButton, new LinearLayout.LayoutParams(0, dp(42), 1));
        newsButton.setOnClickListener(v -> {
            showingRadio = false;
            showLoading();
            offline = false;
            web.loadUrl(PHOTO_URL);
        });

        siteButton = new Button(this);
        siteButton.setAllCaps(false);
        siteButton.setText("Volledige websiteplayer");
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(0, dp(42), 1.6f);
        navLp.leftMargin = dp(5);
        navRow.addView(siteButton, navLp);
        siteButton.setOnClickListener(v -> {
            // Vermijd gelijktijdige audio van de vaste speler en de websiteplayer.
            stopRadio();
            showingRadio = true;
            showLoading();
            offline = false;
            web.loadUrl(RADIO_URL);
        });
    }

    private void toggleRadio() {
        if (playing || preparing) { stopRadio(); return; }
        stopRadio();
        preparing = true;
        playButton.setText("…");
        playButton.setContentDescription("Stream wordt geladen");
        stateLabel.setText("Verbinding maken...");
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
            mp.setDataSource(STREAM_URL);
            mp.setOnPreparedListener(m -> {
                if (player != m || destroyed) return;
                try {
                    m.start();
                    preparing = false;
                    playing = true;
                    playButton.setText("Ⅱ");
                    playButton.setContentDescription("Pauzeer de radio");
                    stateLabel.setText("Radio speelt live");
                    updateTrack();
                } catch (Exception ex) { playbackError(); }
            });
            mp.setOnErrorListener((m, what, extra) -> { playbackError(); return true; });
            mp.prepareAsync();
        } catch (Exception ex) {
            playbackError();
        }
    }

    private void playbackError() {
        stopRadio();
        stateLabel.setText("Stream niet bereikbaar – probeer opnieuw");
    }

    private void stopRadio() {
        preparing = false;
        playing = false;
        if (player != null) {
            try { player.reset(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        if (playButton != null) {
            playButton.setText("▶");
            playButton.setContentDescription("Start de radio");
        }
        if (stateLabel != null) stateLabel.setText("Tik op afspelen om live te luisteren");
    }

    private void updateTrack() {
        if (fetching || destroyed) return;
        fetching = true;
        new Thread(() -> {
            String track = null;
            for (String source : TITLES) {
                try {
                    track = readTrack(source);
                    if (track != null && !track.isEmpty()) break;
                } catch (Exception ignored) {}
            }
            final String finalTitle = track;
            runOnUiThread(() -> {
                fetching = false;
                if (destroyed || nowPlaying == null) return;
                nowPlaying.setText(finalTitle == null || finalTitle.isEmpty()
                    ? "Nummerinformatie tijdelijk niet beschikbaar" : finalTitle);
            });
        }, "FotoWalter-NowPlaying").start();
    }

    private String readTrack(String address) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(4500);
        conn.setReadTimeout(4500);
        conn.setUseCaches(false);
        conn.setRequestProperty("Accept", "text/html,application/json,text/plain");
        conn.setRequestProperty("User-Agent", "FotoWalterApp/1.2");
        try {
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null && sb.length() < 30000) sb.append(line).append('\n');
            }
            String body = sb.toString().trim();
            if (address.contains("7.html")) {
                // Icecast/Shoutcast legacy status: 7,1,120,200,52,192,Artist - Title
                body = body.replaceAll("(?s)<[^>]*>", "").trim();
                String[] parts = body.split(",", 7);
                if (parts.length == 7) return cleanTrack(parts[6]);
            } else {
                JSONObject json = new JSONObject(body);
                JSONObject stats = json.optJSONObject("icestats");
                if (stats == null) return null;
                Object source = stats.opt("source");
                JSONObject trackData = null;
                if (source instanceof JSONObject) trackData = (JSONObject) source;
                if (source instanceof JSONArray && ((JSONArray) source).length() > 0)
                    trackData = ((JSONArray) source).optJSONObject(0);
                if (trackData != null) {
                    String title = trackData.optString("title", "");
                    if (title.isEmpty()) {
                        title = (trackData.optString("artist", "") + " - "
                              + trackData.optString("song", "")).trim();
                    }
                    return cleanTrack(title);
                }
            }
        } finally {
            conn.disconnect();
        }
        return null;
    }

    private String cleanTrack(String raw) {
        if (raw == null) return null;
        String value = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().trim();
        if (value.length() > 250) return null;
        if (value.isEmpty() || value.equals("-") || value.equalsIgnoreCase("unknown")
                || value.equalsIgnoreCase("radio schipluiden")) return null;
        return value;
    }

    private void openOther(Uri uri) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (Exception ignored) {}
    }

    private LinearLayout createPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(26), dp(24), dp(26), dp(24));
        p.setBackgroundColor(INK);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.brand);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(195), dp(195));
        lp.bottomMargin = dp(12);
        p.addView(logo, lp);
        return p;
    }

    private void showLoading() {
        if (overlay != null) browserArea.removeView(overlay);
        LinearLayout p = createPanel();
        ProgressBar spinner = new ProgressBar(this);
        p.addView(spinner);
        overlay = p;
        browserArea.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showOffline() {
        if (offline || destroyed) return;
        offline = true;
        if (overlay != null) browserArea.removeView(overlay);
        LinearLayout p = createPanel();
        TextView message = new TextView(this);
        message.setText("Pagina niet bereikbaar.\nControleer je internetverbinding.");
        message.setTextColor(WHITE);
        message.setTextSize(17);
        message.setGravity(Gravity.CENTER);
        p.addView(message);
        Button retry = new Button(this);
        retry.setAllCaps(false);
        retry.setText("Opnieuw proberen");
        p.addView(retry);
        retry.setOnClickListener(v -> {
            offline = false;
            showLoading();
            web.reload();
        });
        overlay = p;
        browserArea.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else if (showingRadio) {
            showingRadio = false;
            web.loadUrl(PHOTO_URL);
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(refresh);
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle b) {
        web.saveState(b);
        super.onSaveInstanceState(b);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        stopRadio();
        if (web != null) {
            web.stopLoading();
            browserArea.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
