package nl.radioschipluiden.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Live stream-metadata; geen WebView-CORS, geen geheim API-wachtwoord nodig. */
final class TrackMetadata {
    private static final String WP = "https://radioschipluiden.nl/wp-admin/admin-ajax.php?action=rscp_nowplaying";
    private static final String STATUS = "https://c40.radioboss.fm:8298/7.html";
    private static final String SHOUTCAST = "https://c40.radioboss.fm:8298/stats?sid=1&json=1";
    private static final Map<String,String> COVER_CACHE = new ConcurrentHashMap<>();
    private static final Map<String,Long> COVER_CACHE_TIME = new ConcurrentHashMap<>();

    static JSONObject fetch() {
        JSONObject out = fetchJson(WP, 7000);
        if(out == null || out.optString("title").trim().isEmpty()) {
            String title = statusTitle();
            out = fromTitle(title);
        } else if(out.optString("artist").isEmpty() || out.optString("track").isEmpty()) {
            JSONObject titleParts = fromTitle(out.optString("title"));
            try {out.put("artist",titleParts.optString("artist"));out.put("track",titleParts.optString("track"));}catch(Exception ignored){}
        }
        String artist=out.optString("artist").trim();
        String track=out.optString("track").trim();
        if (!artist.isEmpty() && !track.isEmpty() && out.optString("cover").trim().isEmpty()) {
            try{out.put("cover",cover(artist,track));}catch(Exception ignored){}
        }
        return out;
    }

    private static JSONObject fromTitle(String title) {
        JSONObject obj=new JSONObject();
        if(title==null) title="";
        title=title.replaceAll("[\\x00-\\x1F]", " ").trim();
        if(title.length()>180) title=title.substring(0,180);
        String artist="",track="";
        String[] parts=title.split("\\s+[-–]\\s+",2);
        if(parts.length==2){artist=parts[0].trim();track=parts[1].trim();}
        try{obj.put("title",title);obj.put("artist",artist);obj.put("track",track);obj.put("cover","");}catch(Exception ignored){}
        return obj;
    }

    private static String statusTitle(){
        String status=request(STATUS,2048);
        if(status!=null){
            String[] parts=status.replaceAll("<[^>]*>","").trim().split(",",7);
            if(parts.length==7) return parts[6].trim();
        }
        JSONObject data=fetchJson(SHOUTCAST,4096);
        if(data!=null){
            String value=data.optString("songtitle",data.optString("songTitle",data.optString("title", "")));
            return value.trim();
        }
        return "";
    }

    private static String request(String url,int limit){
        HttpURLConnection conn=null;
        try {
            conn=(HttpURLConnection)new URL(url).openConnection();
            conn.setConnectTimeout(4500);conn.setReadTimeout(4500);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent","RadioSchipluidenAndroid/2.1");
            if(conn.getResponseCode()!=200)return null;
            try(InputStream is=conn.getInputStream();ByteArrayOutputStream buf=new ByteArrayOutputStream()){
                byte[] b=new byte[2048];int n;
                while((n=is.read(b))!=-1){buf.write(b,0,n);if(buf.size()>limit)return null;}
                return buf.toString("UTF-8");
            }
        }catch(Exception ignored){return null;}
        finally {if(conn!=null)conn.disconnect();}
    }

    private static JSONObject fetchJson(String url,int limit){
        try{String text=request(url,limit);return text==null?null:new JSONObject(text);}catch(Exception ignored){return null;}
    }

    private static String norm(String t){
        String plain=Normalizer.normalize(t.toLowerCase(Locale.ROOT),Normalizer.Form.NFD).replaceAll("\\p{M}","");
        plain=plain.replaceAll("(?i)\\b(feat|featuring|ft|remastered|radio edit|single version)\\b.*$","");
        return plain.replaceAll("[^a-z0-9]+"," ").trim();
    }
    private static boolean matching(String got,String expected){
        String g=norm(got),e=norm(expected);
        return !e.isEmpty()&&(g.equals(e)||g.startsWith(e+" "));
    }
    private static String cover(String artist,String track){
        String key=artist+"|"+track;
        Long timestamp=COVER_CACHE_TIME.get(key);
        if(timestamp!=null && System.currentTimeMillis()-timestamp<3600_000L)return COVER_CACHE.getOrDefault(key,"");
        String found="";
        try{
            String query=URLEncoder.encode(artist+" "+track,"UTF-8");
            JSONObject data=fetchJson("https://itunes.apple.com/search?media=music&entity=song&country=NL&limit=8&term="+query,70_000);
            JSONArray results=data==null?null:data.optJSONArray("results");
            if(results!=null){
                for(int i=0;i<results.length();i++){
                    JSONObject item=results.optJSONObject(i);
                    if(item==null)continue;
                    if(matching(item.optString("artistName"),artist)&&matching(item.optString("trackName"),track)){
                        String url=item.optString("artworkUrl100","").replaceAll("\\d+x\\d+bb","600x600bb");
                        if(url.startsWith("https://")&&url.length()<2048){found=url;break;}
                    }
                }
            }
        }catch(Exception ignored){}
        COVER_CACHE.put(key,found);COVER_CACHE_TIME.put(key,System.currentTimeMillis());
        return found;
    }
}
