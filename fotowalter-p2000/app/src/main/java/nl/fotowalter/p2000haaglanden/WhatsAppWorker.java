package nl.fotowalter.p2000haaglanden;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.WorkManager;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.Constraints;
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import javax.xml.parsers.DocumentBuilderFactory;
import java.net.URL;
import java.net.URLEncoder;
import java.net.HttpURLConnection;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

public class WhatsAppWorker extends Worker {
    private static final String[] CITIES={"den-haag","delft","zoetermeer","westland","midden-delfland","pijnacker-nootdorp","rijswijk","leidschendam-voorburg","wassenaar"};
    private static final String PREF="whatsapp";
    private static final String TASK="p2000_wa_periodic";
    private static final String ONCE="p2000_wa_once";
    public WhatsAppWorker(@NonNull Context context, @NonNull WorkerParameters params){super(context,params);}
    static void schedule(Context ctx){
        Constraints c=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest req=new PeriodicWorkRequest.Builder(WhatsAppWorker.class,15,TimeUnit.MINUTES).setConstraints(c).addTag("p2000wa").build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(TASK,ExistingPeriodicWorkPolicy.UPDATE,req);
        runOnce(ctx);
    }
    static void runOnce(Context ctx){
        Constraints c=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest req=new OneTimeWorkRequest.Builder(WhatsAppWorker.class).setConstraints(c).addTag("p2000wa").build();
        WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE,ExistingWorkPolicy.KEEP,req);
    }
    static void cancel(Context ctx){
        WorkManager.getInstance(ctx).cancelUniqueWork(TASK);
        WorkManager.getInstance(ctx).cancelUniqueWork(ONCE);
    }
    private static String read(HttpURLConnection conn)throws Exception {
        int status=conn.getResponseCode();
        if(status<200||status>=300)throw new Exception("HTTP "+status);
        try(BufferedReader br=new BufferedReader(new InputStreamReader(conn.getInputStream(),"UTF-8"))){
            StringBuilder sb=new StringBuilder();String s;
            while((s=br.readLine())!=null && sb.length()<1_000_000)sb.append(s).append('\n');
            return sb.toString();
        }
    }
    private static String fetch(String url)throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();
        conn.setConnectTimeout(8000);conn.setReadTimeout(12000);
        conn.setRequestProperty("User-Agent","FotoWalter-P2000-WhatsApp/4.0");
        try{return read(conn);}finally{conn.disconnect();}
    }
    static String sendText(String provider,String phone,String key,String message){
        try{
            String base=provider.equals("textmebot")
                ?"https://api.textmebot.com/send.php?recipient="
                :"https://api.callmebot.com/whatsapp.php?phone=";
            String url=base+URLEncoder.encode(phone,"UTF-8")+
                 "&apikey="+URLEncoder.encode(key,"UTF-8")+
                 "&text="+URLEncoder.encode(message,"UTF-8")+
                 (provider.equals("textmebot")?"&json=yes":"");
            String response=fetch(url).trim();
            if(provider.equals("textmebot")){
                try{
                    JSONObject answer=new JSONObject(response);
                    String status=answer.optString("status","").toLowerCase(java.util.Locale.ROOT);
                    String comment=answer.optString("comment","").trim();
                    if(status.equals("success"))return "";
                    String low=comment.toLowerCase(java.util.Locale.ROOT);
                    if(low.contains("invalid apikey")||low.contains("api key")||low.contains("wrong apikey"))
                        return "API-sleutel niet geldig. Controleer de sleutel in de e-mail van TextMeBot.";
                    if(low.contains("disconnected"))
                        return "Je WhatsApp is nog niet gekoppeld. Open de verbindingslink uit de TextMeBot-e-mail en scan de QR-code.";
                    if(low.contains("trial")||low.contains("expired")||low.contains("subscribe"))
                        return "De proefperiode is verlopen. Bekijk je abonnement bij TextMeBot.";
                    if(low.contains("invalid destination")||low.contains("recipient"))
                        return "Het WhatsApp-nummer is niet geldig of niet bereikbaar. Controleer je landcode.";
                    return "TextMeBot weigert het verzoek: "+(comment.isEmpty()?"onbekende API-fout":comment.substring(0,Math.min(180,comment.length())));
                }catch(org.json.JSONException e){
                    return "Onverwacht antwoord van TextMeBot. Controleer de koppeling in de TextMeBot-portal.";
                }
            }else{
                String lower=response.toLowerCase(java.util.Locale.ROOT);
                if(lower.contains("invalid")||lower.contains("error")||lower.contains("blocked")||lower.contains("expired")||lower.contains("not registered"))
                    return "CallMeBot weigert het verzoek. Controleer je activatie en sleutel.";
                if(lower.contains("message queued")||lower.contains("message sent")||lower.contains("success"))return "";
                return "Onbekend antwoord van CallMeBot. Controleer of je WhatsApp een bericht ontvangt.";
            }
        }catch(Exception e){
            String msg=e.getMessage();
            if(msg!=null&&msg.startsWith("HTTP "))return "WhatsApp-dienst reageert met "+msg+". Probeer later opnieuw of controleer de sleutel.";
            return "Geen verbinding met WhatsApp-dienst. Controleer internet en probeer opnieuw.";
        }
    }
    private static class Entry{
        String id,title,link,city,date,service;
        long time;
    }
    private static String n(Element e,String name){
        NodeList list=e.getElementsByTagName(name);
        return list.getLength()>0?list.item(0).getTextContent().trim():"";
    }
    private static List<Entry> feed(String city)throws Exception{
        String xml=fetch("https://sirenes.app/rss/"+city);
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        Document doc=factory.newDocumentBuilder().parse(new InputSource(new java.io.StringReader(xml)));
        NodeList list=doc.getElementsByTagName("item");
        List<Entry> out=new ArrayList<>();
        for(int i=0;i<list.getLength()&&i<50;i++){
            Element e=(Element)list.item(i); Entry a=new Entry();
            a.title=n(e,"title");a.link=n(e,"link");a.city=city;a.date=n(e,"pubDate");
            String guid=n(e,"guid");
            a.id=!guid.isEmpty()?guid:(!a.link.isEmpty()?a.link:city+a.title+a.date);
            String kind=(a.title+" "+n(e,"description")).toLowerCase(java.util.Locale.ROOT);
            if(kind.contains("brandweer")||kind.startsWith("brw"))a.service="brandweer";
            else if(kind.contains("ambulance")||kind.contains("ambu"))a.service="ambulance";
            else if(kind.contains("politie"))a.service="politie";
            else a.service="overig";
            try{a.time=java.time.ZonedDateTime.parse(a.date,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();}catch(Exception ex){a.time=0L;}
            if(!a.title.isEmpty())out.add(a);
        }
        return out;
    }
    private static LinkedHashSet<String> readSeen(String s){
        LinkedHashSet<String> seen=new LinkedHashSet<>();
        try{
            JSONArray a=new JSONArray(s);
            for(int i=0;i<a.length();i++)seen.add(a.getString(i));
        }catch(Exception ignored){}
        return seen;
    }
    private static String saveSeen(LinkedHashSet<String> seen){
        JSONArray arr=new JSONArray();
        int skip=Math.max(0,seen.size()-2500),i=0;
        for(String s:seen)if(i++>=skip)arr.put(s);
        return arr.toString();
    }
    private static void status(SharedPreferences p,String msg){
        p.edit().putString("status",msg).putLong("lastCheck",System.currentTimeMillis()).apply();
    }
    @NonNull @Override public Result doWork() {
        SharedPreferences p=getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
        if(!p.getBoolean("enabled",false))return Result.success();
        String phone=p.getString("phone",""),key=p.getString("key",""),provider=p.getString("provider","callmebot");
        String city=p.getString("city","all"),services=p.getString("services","brandweer");
        if(phone.isEmpty()||key.isEmpty()){status(p,"Instellingen onvolledig.");return Result.success();}
        List<Entry> all=new ArrayList<>();
        int responses=0;
        for(String one:(city.equals("all")?CITIES:new String[]{city})){
            if(isStopped())break;
            try{all.addAll(feed(one));responses++;}catch(Exception ignored){}
        }
        if(responses==0){status(p,"P2000-feeds niet bereikbaar. Probeer later opnieuw.");return Result.retry();}
        LinkedHashSet<String> seen=readSeen(p.getString("seen",""));
        if(!p.getBoolean("primed",false)){
            for(Entry e:all)seen.add(e.id);
            p.edit().putString("seen",saveSeen(seen)).putBoolean("primed",true).apply();
            status(p,"Eerste controle afgerond. Bestaande meldingen overgeslagen; nieuwe meldingen worden doorgestuurd.");
            return Result.success();
        }
        LinkedHashMap<String,Entry> fresh=new LinkedHashMap<>();
        for(Entry e:all)if(!seen.contains(e.id))fresh.put(e.id,e);
        List<Entry> toSend=new ArrayList<>();
        for(Entry e:fresh.values()){
            if(services.contains(e.service))toSend.add(e);
            else seen.add(e.id);
        }
        // Beperk volume om overbelasting of accountblokkering te vermijden.
        toSend.sort(Comparator.comparingLong(x->x.time));
        if(toSend.size()>3){
            for(int i=0;i<toSend.size()-3;i++)seen.add(toSend.get(i).id);
            toSend=new ArrayList<>(toSend.subList(toSend.size()-3,toSend.size()));
        }
        int sent=0;String failure="";
        for(Entry e:toSend){
            if(isStopped())break;
            String msg="🚨 FotoWalter P2000 Haaglanden\n"+e.title+"\n📍 "+e.city.replace("-"," ")+"\n"+(e.link.isEmpty()?"":("🔗 "+e.link));
            failure=sendText(provider,phone,key,msg);
            if(!failure.isEmpty())break;
            seen.add(e.id);sent++;
            try{Thread.sleep(7000);}catch(InterruptedException x){Thread.currentThread().interrupt();break;}
        }
        p.edit().putString("seen",saveSeen(seen)).apply();
        status(p,failure.isEmpty()?("Controle voltooid: "+sent+" WhatsApp-berichten aangeboden."):("WhatsApp verzenden mislukt: "+failure));
        return failure.isEmpty()?Result.success():Result.retry();
    }
}