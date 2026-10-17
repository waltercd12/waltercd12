package nl.fotowalter.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
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

public class MainActivity extends Activity {
  private static final String HOME = "https://fotowalter.nl/";
  private FrameLayout root;
  private WebView web;
  private View overlay;
  private boolean offline;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setStatusBarColor(Color.rgb(12, 16, 23));
    getWindow().setNavigationBarColor(Color.rgb(12, 16, 23));
    root = new FrameLayout(this);
    web = new WebView(this);
    web.setBackgroundColor(Color.WHITE);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setAllowFileAccess(false);
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    s.setCacheMode(WebSettings.LOAD_DEFAULT);
    web.setWebChromeClient(new WebChromeClient());
    web.setWebViewClient(new WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest r) {
        if (!r.isForMainFrame()) return false;
        Uri u = r.getUrl();
        String host = u.getHost();
        if ("https".equals(u.getScheme()) && host != null &&
           (host.equals("fotowalter.nl") || host.equals("www.fotowalter.nl"))) return false;
        openOther(u);
        return true;
      }
      @Override public void onPageFinished(WebView v, String url) {
        if (!offline && overlay != null) overlay.setVisibility(View.GONE);
      }
      @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError e) {
        if (req.isForMainFrame()) showOffline();
      }
    });
    web.setDownloadListener((url, agent, disp, mime, size) -> openOther(Uri.parse(url)));
    root.addView(web, new FrameLayout.LayoutParams(-1, -1));
    setContentView(root);
    showLoading();
    if (state == null || web.restoreState(state) == null) web.loadUrl(HOME);
  }

  private void openOther(Uri u) {
    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); }
    catch (Exception ignored) { }
  }

  private LinearLayout createPanel() {
    LinearLayout p = new LinearLayout(this);
    p.setOrientation(LinearLayout.VERTICAL);
    p.setGravity(Gravity.CENTER);
    p.setPadding(30, 30, 30, 30);
    p.setBackgroundColor(Color.rgb(12, 16, 23));
    ImageView logo = new ImageView(this);
    logo.setImageResource(R.drawable.brand);
    logo.setAdjustViewBounds(true);
    logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = 28;
    p.addView(logo, lp);
    return p;
  }

  private void showLoading() {
    if (overlay != null) root.removeView(overlay);
    LinearLayout p = createPanel();
    p.addView(new ProgressBar(this), new LinearLayout.LayoutParams(-2, -2));
    overlay = p;
    root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
  }

  private void showOffline() {
    if (offline) return;
    offline = true;
    if (overlay != null) root.removeView(overlay);
    LinearLayout p = createPanel();
    TextView text = new TextView(this);
    text.setText("FotoWalter is niet bereikbaar.\nControleer je internetverbinding.");
    text.setTextColor(Color.WHITE);
    text.setTextSize(18);
    text.setGravity(Gravity.CENTER);
    p.addView(text);
    Button retry = new Button(this);
    retry.setText("Opnieuw proberen");
    p.addView(retry);
    retry.setOnClickListener(v -> { offline = false; showLoading(); web.loadUrl(HOME); });
    overlay = p;
    root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
  }

  @Override public void onBackPressed() {
    if (web.canGoBack()) web.goBack();
    else super.onBackPressed();
  }
  @Override protected void onSaveInstanceState(Bundle b) {
    web.saveState(b);
    super.onSaveInstanceState(b);
  }
  @Override protected void onDestroy() {
    web.stopLoading();
    web.destroy();
    super.onDestroy();
  }
}
