from pathlib import Path
p=Path('app/src/main/java/nl/radioschipluiden/app/SchipluidenCastController.java')
s=p.read_text()
start=s.index('        int density=(int)(activity.getResources().getDisplayMetrics().density*1);')
end=s.index('        CastButtonFactory.setUpMediaRouteButton(activity,button);',start)+len('        CastButtonFactory.setUpMediaRouteButton(activity,button);')
replacement='''        // The visible Netflix-style Cast control lives INSIDE the HTML player.
        // Keep a tiny attached native MediaRouteButton to use the official Cast dialog.
        button=new MediaRouteButton(activity);
        FrameLayout.LayoutParams hidden=new FrameLayout.LayoutParams(2,2);
        hidden.gravity=android.view.Gravity.BOTTOM|android.view.Gravity.RIGHT;
        root.addView(button,hidden);
        button.setAlpha(0.01f);
        CastButtonFactory.setUpMediaRouteButton(activity,button);'''
s=s[:start]+replacement+s[end:]
mark='    public void onStart(){'
assert mark in s
s=s.replace(mark,'''    /** Open official Cast selector from the in-player Cast icon. */
    public boolean showPicker(){
        try { return button.showDialog(); }
        catch (RuntimeException err) {
            android.util.Log.w("RadioSchipluiden","Cast dialog unavailable",err);
            return false;
        }
    }
'''+mark,1)
p.write_text(s)

main=Path('app/src/main/java/nl/radioschipluiden/app/MainActivity.java')
s=main.read_text()
assert 'extends AppCompatActivity' in s
s=s.replace('import android.util.Log;', '''import android.util.Log;
import android.net.Uri;
import android.widget.Toast;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;''',1)
mark='    private SchipluidenCastController castController;'
assert mark in s
s=s.replace(mark,mark+'''
    // Current APK version is compared against a public, read-only WordPress endpoint.
    // No push token, identifier or personal information is sent.
    private static final String UPDATE_INFO =
        "https://radioschipluiden.nl/wp-json/radioschipluiden/v1/app/android";
    private static final String UPDATE_DOWNLOAD =
        "https://radioschipluiden.nl/wp-json/radioschipluiden/v1/download/android";
    private boolean updateCheckStarted=false;
    private volatile int availableUpdateCode=0;
    private volatile String availableUpdateName="";
''',1)
mark='                if (lastSong.length() > 0) deliverSong(lastSong);'
assert mark in s
s=s.replace(mark,mark+'\n                displayAvailableUpdate();',1)
mark='    private final class Controls {'
assert mark in s
s=s.replace(mark,'''    private void displayAvailableUpdate() {
        if (webView==null || !pageReady) return;
        if (availableUpdateCode>0) {
            evaluate("window.RadioUpdates && window.RadioUpdates.show("+
                JSONObject.quote(availableUpdateName)+");");
        } else {
            evaluate("window.RadioUpdates && window.RadioUpdates.hide();");
        }
    }

    private void checkForUpdates() {
        new Thread(() -> {
            HttpURLConnection connection=null;
            try {
                connection=(HttpURLConnection)new URL(UPDATE_INFO).openConnection();
                connection.setConnectTimeout(5500);
                connection.setReadTimeout(5500);
                connection.setRequestProperty("Accept","application/json");
                connection.setUseCaches(false);
                if (connection.getResponseCode()!=200)return;
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                try(InputStream in=connection.getInputStream()){
                    byte[] buffer=new byte[2048]; int count;
                    while((count=in.read(buffer))!=-1 && out.size()<32768)
                        out.write(buffer,0,count);
                }
                JSONObject remote=new JSONObject(out.toString("UTF-8"));
                int code=remote.optInt("version_code",0);
                int installed=(int)getPackageManager().getPackageInfo(getPackageName(),0).getLongVersionCode();
                String name=remote.optString("version_name","Nieuwe versie");
                if(code>installed && code<1000000){
                    availableUpdateCode=code;
                    availableUpdateName=name.length()<80?name:"Nieuwe versie";
                } else {
                    availableUpdateCode=0;
                    availableUpdateName="";
                }
                runOnUiThread(this::displayAvailableUpdate);
            } catch(Exception e) {
                // A temporary network failure must NEVER prevent the radio from opening.
                Log.i("RadioSchipluiden","Update check unavailable",e);
            } finally {
                if(connection!=null)connection.disconnect();
            }
        },"RadioSchipluiden-UpdateCheck").start();
    }

'''+mark,1)
mark='    private final class Controls {'
# Add bridge methods after class declaration.
assert mark in s
s=s.replace(mark,'''    private final class Controls {
        @JavascriptInterface public void showCast(){
            runOnUiThread(() -> {
                try {
                    if(castController==null || !castController.showPicker())
                        Toast.makeText(MainActivity.this,
                            "Geen Cast-apparaat gevonden. Controleer wifi en Google Play-services.",
                            Toast.LENGTH_LONG).show();
                } catch (RuntimeException | LinkageError e) {
                    Toast.makeText(MainActivity.this,"Cast is op dit toestel niet beschikbaar.",
                        Toast.LENGTH_LONG).show();
                }
            });
        }
        @JavascriptInterface public void openUpdate(){
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(UPDATE_DOWNLOAD))
                        .addCategory(Intent.CATEGORY_BROWSABLE));
                } catch (RuntimeException e) {
                    Toast.makeText(MainActivity.this,"Open radioschipluiden.nl om de app te downloaden.",
                        Toast.LENGTH_LONG).show();
                }
            });
        }''',1)
mark='        super.onStart();'
assert mark in s
s=s.replace(mark,mark+'''
        if (!updateCheckStarted) { updateCheckStarted=true; checkForUpdates(); }''',1)
main.write_text(s)

# Required version: Code 13. No changes to existing audio focus service.
build=Path('app/build.gradle')
lines=build.read_text().splitlines()
lines=["        versionCode 13" if l.strip().startswith("versionCode ")
       else "        versionName '3.0-Cast-Updates'" if l.strip().startswith("versionName ")
       else l for l in lines]
build.write_text('\n'.join(lines)+'\n')
assert "versionCode 13" in build.read_text()
assert 'window.RadioUpdates' in main.read_text() and 'showCast()' in main.read_text()
print("Google Cast integrated within player and startup update checks installed; code 13")
