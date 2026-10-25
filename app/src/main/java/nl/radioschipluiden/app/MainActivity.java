package nl.radioschipluiden.app;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.webkit.*;
import android.view.View;
import android.view.WindowInsets;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private WebView web;
    private boolean loaded = false;
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if(RadioService.EVENT.equals(intent.getAction())) sendState(intent.getStringExtra("stage"));
        }
    };
    public final class Bridge {
        @JavascriptInterface public void toggle() {
            runOnUiThread(() -> {
                String current = RadioService.stage;
                Intent action = new Intent(MainActivity.this, RadioService.class);
                if ("intro".equals(current) || "connecting".equals(current) || "playing".equals(current) || "buffering".equals(current)) {
                    action.setAction(RadioService.STOP); startService(action);
                } else {
                    if(Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                       requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 31);
                    action.setAction(RadioService.PLAY); startForegroundService(action);
                    sendState("intro");
                }
            });
        }
    }
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(8,20,35));getWindow().setNavigationBarColor(Color.rgb(8,20,35));
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(8,20,35));web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(),"RadioAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView w,String url) {loaded=true;sendState(RadioService.stage);}
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request) {
                android.net.Uri uri=request.getUrl();
                if("file".equals(uri.getScheme()))return false;
                startActivity(new Intent(Intent.ACTION_VIEW,uri));return true;
            }
        });
        setContentView(web);
        if (Build.VERSION.SDK_INT>=33) registerReceiver(updates,new IntentFilter(RadioService.EVENT),Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updates,new IntentFilter(RadioService.EVENT));
        web.loadUrl("file:///android_asset/index.html");
    }
    private void sendState(String stage) {
        if(loaded && web!=null) web.evaluateJavascript("window.RadioPlayer&&window.RadioPlayer.setNativeState("+JSONObject.quote(stage==null?"off":stage)+")",null);
    }
    @Override protected void onDestroy() {unregisterReceiver(updates);if(web!=null){web.destroy();web=null;}super.onDestroy();}
}
