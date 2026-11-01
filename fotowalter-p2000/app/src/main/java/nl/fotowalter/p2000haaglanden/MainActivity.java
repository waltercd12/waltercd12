package nl.fotowalter.p2000haaglanden;

import android.app.Activity;
import android.content.SharedPreferences;
import androidx.work.WorkManager;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import java.util.concurrent.TimeUnit;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.graphics.Color;
import android.view.WindowManager;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xml.sax.InputSource;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private WebView web;
    private final ExecutorService executor = Executors.newFixedThreadPool(6);
    private final AtomicBoolean fetching = new AtomicBoolean(false);
    private final java.util.concurrent.ConcurrentHashMap<String,String> feedErrors=new java.util.concurrent.ConcurrentHashMap<>();
    private final String[] slugs={"den-haag","delft","zoetermeer","westland","midden-delfland","pijnacker-nootdorp","rijswijk","leidschendam-voorburg","wassenaar"};

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(10,14,22));
        getWindow().setNavigationBarColor(Color.rgb(10,14,22));
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(9,13,22));
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "P2000");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri uri=req.getUrl();
                if("file".equals(uri.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                return true;
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        try {
            SharedPreferences p=getSharedPreferences("whatsapp",MODE_PRIVATE);
            if(p.getBoolean("enabled",false)) WhatsAppWorker.schedule(this);
        }catch(Exception ignored){}

    }

    private static String node(Element e,String name){
        NodeList n=e.getElementsByTagName(name);
        return n.getLength()>0 && n.item(0)!=null ? n.item(0).getTextContent().trim():"";
    }
    private static String readAll(InputStream in)throws Exception {
        BufferedReader br=new BufferedReader(new InputStreamReader(in,"UTF-8"));
        StringBuilder s=new StringBuilder(); String line;
        while((line=br.readLine())!=null)s.append(line).append('\n');
        return s.toString();
    }
    private static String http(String url)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(7000); c.setReadTimeout(7000);
        c.setRequestProperty("User-Agent","FotoWalter-P2000-Android/3.0");
        c.setRequestProperty("Accept","application/rss+xml, application/xml, application/json, */*");
        try {
            if(c.getResponseCode()!=200)throw new Exception("HTTP "+c.getResponseCode());
            return readAll(c.getInputStream());
        }finally {c.disconnect();}
    }
    private JSONArray feed(String slug) {
        JSONArray result=new JSONArray();
        try {
            String xml=http("https://sirenes.app/rss/"+slug);
            org.xmlpull.v1.XmlPullParser p=android.util.Xml.newPullParser();
            p.setInput(new java.io.StringReader(xml));
            JSONObject current=null;
            int event=p.getEventType();
            while(event!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT && result.length()<50){
                if(event==org.xmlpull.v1.XmlPullParser.START_TAG) {
                    String tag=p.getName();
                    if("item".equals(tag)){
                        current=new JSONObject();
                        current.put("slug",slug);
                    }else if(current!=null){
                        String field=null;
                        if("title".equals(tag))field="title";
                        else if("description".equals(tag))field="description";
                        else if("pubDate".equals(tag))field="date";
                        else if("link".equals(tag))field="link";
                        else if("guid".equals(tag))field="guid";
                        else if("point".equals(tag)||"georss:point".equals(tag))field="point";
                        if(field!=null) current.put(field,p.nextText());
                    }
                }else if(event==org.xmlpull.v1.XmlPullParser.END_TAG && "item".equals(p.getName())){
                    if(current!=null&&!current.optString("title").isEmpty()){
                        String point=current.optString("point");
                        if(!point.isEmpty()){
                            String[] nums=point.trim().split("\\s+");
                            try{
                                if(nums.length==2){
                                    current.put("lat",Double.parseDouble(nums[0]));
                                    current.put("lon",Double.parseDouble(nums[1]));
                                }
                            }catch(NumberFormatException ignored){}
                        }
                        result.put(current);
                    }
                    current=null;
                }
                event=p.next();
            }
            feedErrors.remove(slug);
        }catch(Exception e){
            String why=e.getClass().getSimpleName()+(e.getMessage()==null?"":": "+e.getMessage());
            feedErrors.put(slug,why);
            android.util.Log.e("FotoWalterP2000","RSS "+slug+": "+why,e);
        }
        return result;
    }
        private void invoke(String js) {
        runOnUiThread(()->{ if(web!=null)web.evaluateJavascript(js,null); });
    }
    private class Bridge {
        @JavascriptInterface public void request(String municipality) {
            if(!fetching.compareAndSet(false,true))return;
            executor.execute(()->{
                JSONArray out=new JSONArray(); int successes=0;
                String[] use=municipality.equals("all")?slugs:new String[]{municipality};
                List<Callable<JSONArray>> tasks=new ArrayList<>();
                for(String slug:use)tasks.add(()->feed(slug));
                try {
                    List<Future<JSONArray>> futures=executor.invokeAll(tasks,16,TimeUnit.SECONDS);
                    for(Future<JSONArray> future:futures){
                        try {
                            JSONArray one=future.get(100,TimeUnit.MILLISECONDS);
                            if(one.length()>0)successes++;
                            for(int i=0;i<one.length();i++)out.put(one.getJSONObject(i));
                        }catch(Exception ignored){}
                    }
                }catch(Exception ignored){}
                fetching.set(false);
                JSONObject payload=new JSONObject();
                try{
                    payload.put("items",out);
                    payload.put("sources",successes);
                    payload.put("totalSources",use.length);
                    if(successes==0 && !feedErrors.isEmpty()) payload.put("error",feedErrors.values().iterator().next());
                }catch(Exception ignored){}
                invoke("window.receiveFeeds("+payload.toString()+");");
            });
        }
        @JavascriptInterface public void geocode(String id,String street,String municipality) {
            executor.execute(()->{
                try {
                    String q=java.net.URLEncoder.encode(street+" "+municipality+" Zuid-Holland","UTF-8");
                    String json=http("https://api.pdok.nl/bzk/locatieserver/search/v3_1/free?rows=2&fq=type%3Aweg&q="+q);
                    JSONObject obj=new JSONObject(json);
                    JSONArray docs=obj.getJSONObject("response").getJSONArray("docs");
                    for(int i=0;i<docs.length();i++){
                        JSONObject d=docs.getJSONObject(i);
                        String n=d.optString("straatnaam",d.optString("weergavenaam","")).toLowerCase();
                        String want=street.toLowerCase();
                        if(!n.contains(want)&&!want.contains(n))continue;
                        String p=d.optString("centroide_ll","");
                        if(p.startsWith("POINT(")&&p.endsWith(")")){
                            String[] xy=p.substring(6,p.length()-1).split(" ");
                            double lat=Double.parseDouble(xy[1]),lon=Double.parseDouble(xy[0]);
                            if(lat>51.7&&lat<52.4&&lon>3.6&&lon<4.9){
                                JSONObject res=new JSONObject();
                                res.put("id",id);res.put("lat",lat);res.put("lon",lon);
                                invoke("window.locationReady("+res.toString()+");");
                            }
                            break;
                        }
                    }
                }catch(Exception ignored){}
            });
        }
        @JavascriptInterface public void getWaConfig(){
            SharedPreferences p=getSharedPreferences("whatsapp",MODE_PRIVATE);
            JSONObject o=new JSONObject();
            try{
                o.put("phone",p.getString("phone",""));
                o.put("hasKey",!p.getString("key","").isEmpty());
                o.put("provider",p.getString("provider","textmebot"));
                o.put("city",p.getString("city","all"));
                o.put("services",p.getString("services","brandweer"));
                o.put("enabled",p.getBoolean("enabled",false));
                o.put("verified",p.getBoolean("verified",false));
                o.put("status",p.getString("status","Nog geen meldingencontrole uitgevoerd."));
                o.put("last",p.getLong("lastCheck",0));
            }catch(Exception ignored){}
            invoke("window.waConfigured("+o.toString()+");");
        }
        @JavascriptInterface public void saveWaConfig(String phone,String key,String provider,String city,String services,boolean enabled){
            SharedPreferences p=getSharedPreferences("whatsapp",MODE_PRIVATE);
            String normalized=phone.replace(" ","").replace("-","").replace("(","").replace(")","");
            if(!normalized.matches("\\+?[0-9]{10,15}")){
                invoke("window.waSaved({ok:false,message:'Voer een geldig internationaal telefoonnummer in, bijvoorbeeld +31612345678.'});");return;
            }
            if(!provider.equals("callmebot") && !provider.equals("textmebot")){
                invoke("window.waSaved({ok:false,message:'Kies een geldige WhatsApp-dienst.'});");return;
            }
            if(!city.equals("all")&&!java.util.Arrays.asList(slugs).contains(city)){
                invoke("window.waSaved({ok:false,message:'Ongeldige gemeente.'});");return;
            }
            if(!services.contains("brandweer") && !services.contains("ambulance") && !services.contains("politie")){
                invoke("window.waSaved({ok:false,message:'Selecteer minstens één hulpdienst.'});");return;
            }
            String savedKey=key.trim().isEmpty()?p.getString("key",""):key.trim();
            if(enabled && savedKey.isEmpty()){
                invoke("window.waSaved({ok:false,message:'Vul eerst je persoonlijke API-sleutel in.'});");return;
            }
            p.edit().putString("phone",normalized).putString("key",savedKey)
                .putString("provider",provider).putString("city",city).putString("services",services)
                .putBoolean("enabled",enabled).putBoolean("primed",false)
                .putString("status",enabled?"Actief. Eerste controle slaat bestaande meldingen over.":"WhatsApp-doorsturen staat uit.")
                .apply();
            if(enabled) WhatsAppWorker.schedule(MainActivity.this);
            else WhatsAppWorker.cancel(MainActivity.this);
            invoke("window.waSaved({ok:true,message:'Instellingen opgeslagen. '+(enabled?'Controle elke circa 15 minuten.':'Doorsturen uitgeschakeld.')});");
        }
        @JavascriptInterface public void connectWhatsApp(String phone,String key,String provider,String city,String services){
            final String normalized=phone.replace(" ","").replace("-","").replace("(","").replace(")","");
            if(!normalized.matches("\\+?[0-9]{10,15}")){
                invoke("window.waConnect({ok:false,message:'Gebruik je nummer met landcode, bijvoorbeeld +31612345678.'});");return;
            }
            if(!provider.equals("callmebot")&&!provider.equals("textmebot")){
                invoke("window.waConnect({ok:false,message:'Kies een WhatsApp-dienst.'});");return;
            }
            if(!city.equals("all")&&!java.util.Arrays.asList(slugs).contains(city)){
                invoke("window.waConnect({ok:false,message:'Kies een geldige gemeente.'});");return;
            }
            if(!services.contains("brandweer")&&!services.contains("ambulance")&&!services.contains("politie")){
                invoke("window.waConnect({ok:false,message:'Selecteer minstens één hulpdienst.'});");return;
            }
            final SharedPreferences prefs=getSharedPreferences("whatsapp",MODE_PRIVATE);
            final String secret=key.trim().isEmpty()?prefs.getString("key",""):key.trim();
            if(secret.length()<3){
                invoke("window.waConnect({ok:false,message:'Vraag eerst een eigen API-sleutel aan via stap 1.'});");return;
            }
            executor.execute(()->{
                String error=WhatsAppWorker.sendText(provider,normalized,secret,"FotoWalter P2000 Haaglanden: test van je WhatsApp-koppeling.");
                try{
                    JSONObject result=new JSONObject();
                    if(error.isEmpty()){
                        boolean old=prefs.getBoolean("enabled",false);
                        boolean changed=!normalized.equals(prefs.getString("phone",""))||!secret.equals(prefs.getString("key",""))||
                            !provider.equals(prefs.getString("provider",""))||!city.equals(prefs.getString("city",""));
                        SharedPreferences.Editor edit=prefs.edit()
                          .putString("phone",normalized).putString("key",secret).putString("provider",provider)
                          .putString("city",city).putString("services",services)
                          .putBoolean("verified",true).putBoolean("enabled",true)
                          .putString("status","API heeft het testbericht geaccepteerd. Controleer of je het op WhatsApp hebt ontvangen.");
                        if(changed||!old)edit.putBoolean("primed",false);
                        edit.apply();
                        WhatsAppWorker.schedule(MainActivity.this);
                        result.put("ok",true).put("message","De API heeft je testbericht geaccepteerd en het doorsturen staat aan. Controleer nu WhatsApp. De eerste achtergrondcontrole slaat oude meldingen over.");
                    }else{
                        result.put("ok",false).put("message",error);
                    }
                    invoke("window.waConnect("+result.toString()+");");
                }catch(Exception ignored){}
            });
        }
        @JavascriptInterface public void testWa(){
            SharedPreferences p=getSharedPreferences("whatsapp",MODE_PRIVATE);
            String phone=p.getString("phone",""),key=p.getString("key",""),provider=p.getString("provider","callmebot");
            if(phone.isEmpty()||key.isEmpty()){invoke("window.waTest({ok:false,message:'Sla eerst je nummer en API-sleutel op.'});");return;}
            long prev=p.getLong("lastTest",0);
            if(System.currentTimeMillis()-prev<60000){invoke("window.waTest({ok:false,message:'Maximaal één test per minuut.'});");return;}
            p.edit().putLong("lastTest",System.currentTimeMillis()).apply();
            executor.execute(()->{
                String error=WhatsAppWorker.sendText(provider,phone,key,"FotoWalter P2000 Haaglanden: testmelding. Je WhatsApp-koppeling is bereikbaar.");
                try{
                    JSONObject result=new JSONObject().put("ok",error.isEmpty()).put("message",error.isEmpty()?"Bericht aangeboden aan WhatsApp-dienst. Controleer je WhatsApp.":"Verzenden mislukt: "+error);
                    invoke("window.waTest("+result.toString()+");");
                }catch(Exception ignored){}
            });
        }
        @JavascriptInterface public void checkWaNow(){
            SharedPreferences p=getSharedPreferences("whatsapp",MODE_PRIVATE);
            if(!p.getBoolean("enabled",false)){invoke("window.waTest({ok:false,message:'Zet WhatsApp-doorsturen eerst aan.'});");return;}
            WhatsAppWorker.runOnce(MainActivity.this);
            invoke("window.waTest({ok:true,message:'Controle aangevraagd. Open Status om de uitslag te bekijken.'});");
        }
        @JavascriptInterface public void external(String url) {
            if(!url.startsWith("https://"))return;
            runOnUiThread(()->{
                try{ startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))); }catch(Exception ignored){}
            });
        }
    }
    @Override public void onBackPressed() {
        if(web!=null && web.canGoBack())web.goBack();
        else super.onBackPressed();
    }
    @Override protected void onDestroy(){
        if(web!=null){web.removeJavascriptInterface("P2000");web.destroy();web=null;}
        executor.shutdownNow();
        super.onDestroy();
    }
}
