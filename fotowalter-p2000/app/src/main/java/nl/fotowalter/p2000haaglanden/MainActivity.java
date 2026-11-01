package nl.fotowalter.p2000haaglanden;

import android.app.Activity;
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
            DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            f.setFeature("http://xml.org/sax/features/external-general-entities",false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            Document d=f.newDocumentBuilder().parse(new InputSource(new java.io.StringReader(xml)));
            NodeList items=d.getElementsByTagName("item");
            for(int i=0;i<items.getLength() && i<50;i++){
                Element e=(Element)items.item(i);
                String title=node(e,"title");
                if(title.isEmpty())continue;
                JSONObject o=new JSONObject();
                o.put("title",title);
                o.put("description",node(e,"description"));
                o.put("date",node(e,"pubDate"));
                o.put("link",node(e,"link"));
                o.put("guid",node(e,"guid"));
                o.put("slug",slug);
                String point=node(e,"georss:point");
                if(!point.isEmpty()) {
                    String[] nums=point.trim().split("\\s+");
                    if(nums.length==2) {o.put("lat",Double.parseDouble(nums[0]));o.put("lon",Double.parseDouble(nums[1]));}
                }
                result.put(o);
            }
        }catch(Exception ignored){}
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
