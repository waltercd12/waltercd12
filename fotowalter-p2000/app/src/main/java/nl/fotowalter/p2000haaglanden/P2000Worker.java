package nl.fotowalter.p2000haaglanden;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.WorkManager;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class P2000Worker extends Worker {
    public static final String PREFS = "fw_p2000_android";
    private static final String JOB = "fotowalter-p2000-monitor-v4";
    private static final String CHANNEL = "fotowalter-p2000-alerts";
    private static final String[] SLUGS = {
            "den-haag", "delft", "zoetermeer", "westland", "midden-delfland",
            "pijnacker-nootdorp", "rijswijk", "leidschendam-voorburg", "wassenaar"
    };

    private static class Alert {
        String id, title, link, slug, category;
        long timestamp;
        Alert(String id, String title, String link, String slug, String category, long timestamp) {
            this.id=id; this.title=title; this.link=link; this.slug=slug;
            this.category=category; this.timestamp=timestamp;
        }
    }

    public P2000Worker(@NonNull Context context,@NonNull WorkerParameters params) { super(context,params); }

    public static void schedule(Context context) {
        SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        boolean enabled=p.getBoolean("native_alerts",false)||p.getBoolean("wa_enabled",false);
        if(!enabled) {
            WorkManager.getInstance(context).cancelUniqueWork(JOB);
            return;
        }
        Constraints c=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest r=new PeriodicWorkRequest.Builder(P2000Worker.class,15,TimeUnit.MINUTES)
                .setConstraints(c).build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(JOB,ExistingPeriodicWorkPolicy.KEEP,r);
    }

    private static String xmlNode(Element e, String name) {
        NodeList n=e.getElementsByTagName(name);
        return n.getLength()>0?n.item(0).getTextContent().trim():"";
    }
    private static String getHttp(String url)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(6000);c.setReadTimeout(7000);
        c.setRequestProperty("User-Agent","FotoWalter-P2000-Android/4.0");
        c.setRequestProperty("Accept","application/rss+xml,application/xml,*/*");
        try {
            if(c.getResponseCode()!=200)throw new Exception("HTTP "+c.getResponseCode());
            StringBuilder b=new StringBuilder();String line;
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"))) {
                while((line=reader.readLine())!=null)b.append(line).append('\n');
            }
            return b.toString();
        }finally {c.disconnect();}
    }

    private static long pubTime(String s) {
        String[] formats={"EEE, dd MMM yyyy HH:mm:ss Z","EEE, dd MMM yyyy HH:mm Z","yyyy-MM-dd'T'HH:mm:ssXXX"};
        for(String pattern:formats) {
            try {
                SimpleDateFormat fmt=new SimpleDateFormat(pattern,Locale.US);
                fmt.setLenient(false);
                Date d=fmt.parse(s);if(d!=null)return d.getTime();
            }catch(Exception ignored){}
        }
        return 0;
    }
    private static String category(String title) {
        String s=title.toLowerCase(Locale.ROOT);
        if(s.contains("ambulance")||s.contains("ambu")||s.contains("mka"))return "ambulance";
        if(s.contains("politie"))return "politie";
        if(s.contains("brandweer")||s.contains("brw")||s.contains("brand"))return "brandweer";
        return "overig";
    }
    private static String hash(String s) {
        try {
            byte[] d=MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            StringBuilder out=new StringBuilder();
            for(int i=0;i<16;i++)out.append(String.format(Locale.ROOT,"%02x",d[i]&255));
            return out.toString();
        }catch(Exception e){return Integer.toString(s.hashCode());}
    }

    private static int fetch(String slug, List<Alert> all) {
        try {
            String xml=getHttp("https://sirenes.app/rss/"+slug);
            DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            f.setFeature("http://xml.org/sax/features/external-general-entities",false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            Document d=f.newDocumentBuilder().parse(new InputSource(new java.io.StringReader(xml)));
            NodeList nodes=d.getElementsByTagName("item");
            for(int i=0;i<Math.min(50,nodes.getLength());i++){
                Element e=(Element)nodes.item(i);
                String title=xmlNode(e,"title"),link=xmlNode(e,"link");
                if(title.isEmpty())continue;
                String guid=xmlNode(e,"guid");
                String id=hash(slug+"|"+(!guid.isEmpty()?guid:link+"|"+title+"|"+xmlNode(e,"pubDate")));
                all.add(new Alert(id,title,link,slug,category(title),pubTime(xmlNode(e,"pubDate"))));
            }
            return 1;
        }catch(Exception e){return 0;}
    }

    public static String sendWhatsApp(String phone, String key, String message) throws Exception {
        if(!phone.matches("[0-9]{10,16}")||key.length()<5||key.length()>180)throw new Exception("Vul een geldig telefoonnummer en een API-sleutel in.");
        URL u=new URL("https://api.textmebot.com/send.php");
        HttpURLConnection conn=(HttpURLConnection)u.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(13000);
        conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
        String content="recipient="+URLEncoder.encode("+"+phone,"UTF-8")+
                "&apikey="+URLEncoder.encode(key,"UTF-8")+
                "&text="+URLEncoder.encode(message,"UTF-8")+"&json=yes";
        try {
            try(OutputStreamWriter w=new OutputStreamWriter(conn.getOutputStream(),"UTF-8")){w.write(content);}
            int status=conn.getResponseCode();
            String body="";
            if(status>=200&&status<300) {
                StringBuilder b=new StringBuilder();
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(conn.getInputStream(),"UTF-8"))){
                    String line;while((line=reader.readLine())!=null&&b.length()<6000)b.append(line);
                }
                body=b.toString();
            }
            if(status<200||status>=300)throw new Exception("WhatsApp-dienst gaf HTTP "+status);
            String lower=body.toLowerCase(Locale.ROOT);
            if(lower.contains("\"success\":false")||lower.contains("\"status\":\"error\"")||
                    lower.contains("\"status\": \"error\"")||lower.contains("\"error\":")||
                    lower.contains("invalid apikey")||lower.contains("trial period is over")) {
                throw new Exception("WhatsApp-dienst heeft verzending geweigerd.");
            }
            return "Aanvraag verzonden. Controleer WhatsApp; ontvangst is niet gegarandeerd.";
        }finally{conn.disconnect();}
    }

    private void notifyOne(Alert item, int id) {
        Context context=getApplicationContext();
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(manager==null)return;
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel(CHANNEL,"P2000 Haaglanden",NotificationManager.IMPORTANCE_DEFAULT);
            c.setDescription("Nieuwe openbare P2000-meldingen");
            manager.createNotificationChannel(c);
        }
        Intent intent=new Intent(context,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(context,CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🚨 P2000: "+item.slug)
                .setContentText(item.title)
                .setStyle(new Notification.BigTextStyle().bigText(item.title))
                .setAutoCancel(true).setContentIntent(pi).build();
        manager.notify(id,n);
    }

    @NonNull @Override public Result doWork() {
        Context ctx=getApplicationContext();
        SharedPreferences p=ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        boolean nativeAlerts=p.getBoolean("native_alerts",false);
        boolean waEnabled=p.getBoolean("wa_enabled",false);
        if(!nativeAlerts&&!waEnabled)return Result.success();
        String region=p.getString("region","all");
        String service=p.getString("service","all");
        List<Alert> all=new ArrayList<>();
        int sources=0;
        for(String slug:SLUGS) {
            if(!region.equals("all")&&!region.equals(slug))continue;
            sources+=fetch(slug,all);
        }
        if(sources==0){p.edit().putString("last_error","P2000-bron niet bereikbaar").apply();return Result.retry();}
        all.sort(Comparator.comparingLong(a->a.timestamp));
        Set<String> known=new HashSet<>(p.getStringSet("known_ids",new HashSet<String>()));
        boolean initialized=p.getBoolean("initialized",false);
        long now=System.currentTimeMillis();
        int notifCount=0,waCount=0;
        String date=new SimpleDateFormat("yyyyMMdd",Locale.ROOT).format(new Date());
        int dayCount=date.equals(p.getString("day",""))?p.getInt("day_count",0):0;
        String phone=p.getString("wa_phone",""),key=p.getString("wa_key","");
        boolean canWA=waEnabled&&phone.matches("[0-9]{10,16}")&&key.length()>=5;
        String lastError="";
        for(Alert item:all){
            if(known.contains(item.id))continue;
            if(initialized && item.timestamp>0 && now-item.timestamp>=0 && now-item.timestamp<45*60*1000L &&
                    (service.equals("all")||service.equals(item.category))) {
                if(nativeAlerts&&notifCount<5){notifyOne(item,(item.id.hashCode()&0x7fffffff));notifCount++;}
                if(canWA&&waCount<2&&dayCount<20){
                    try {
                        sendWhatsApp(phone,key,"🚨 FotoWalter P2000 Haaglanden\n📍 "+item.slug+
                                "\n⏰ "+new SimpleDateFormat("dd-MM HH:mm",Locale.forLanguageTag("nl-NL")).format(new Date(item.timestamp))+
                                "\n"+item.title+"\n"+item.link);
                        waCount++;dayCount++;
                        if(waCount<2)Thread.sleep(6000);
                    }catch(Exception e){lastError=e.getMessage();canWA=false;}
                }
            }
        }
        // Keep the IDs of recently observed messages; never send old messages at first install.
        LinkedHashSet<String> fresh=new LinkedHashSet<>();
        for(Alert item:all)fresh.add(item.id);
        for(String id:known){if(fresh.size()>=850)break;fresh.add(id);}
        p.edit().putStringSet("known_ids",fresh).putBoolean("initialized",true)
                .putLong("last_scan",now).putString("day",date).putInt("day_count",dayCount)
                .putString("last_error",lastError).apply();
        return Result.success();
    }
}
