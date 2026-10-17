package nl.radioschipluiden.app;

import android.content.*;
import android.graphics.*;
import android.media.*;
import android.media.browse.*;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import android.service.media.MediaBrowserService;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Android Auto media-browser integration, forwarding playback to the resilient foreground RadioService. */
public final class AutoMediaService extends MediaBrowserService {
  private static final String ROOT="radio_schipluiden", STATION="schipluiden_live";
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final ExecutorService executor=Executors.newSingleThreadExecutor();
  private MediaSession session; private Bitmap icon; private boolean registered=false, destroyed=false;
  private String song=""; private String title="Radio Schipluiden", artist="Live radio";
  private final BroadcastReceiver updates=new BroadcastReceiver(){
    @Override public void onReceive(Context c, Intent intent){
      if(RadioService.ACTION_STATE.equals(intent.getAction())) {
        setState(intent.getStringExtra(RadioService.EXTRA_STATE));
      }
    }
  };
  private final Runnable poll=new Runnable(){
    @Override public void run(){
      if(destroyed)return;
      executor.execute(()->{
        String current=fetchTitle();
        if(!current.isEmpty()) handler.post(()->{
          if(destroyed||song.equals(current))return;
          song=current;
          int p=current.indexOf(" - ");
          artist=p>0?current.substring(0,p).trim():"Radio Schipluiden";
          title=p>0?current.substring(p+3).trim():current;
          showMetadata();
        });
      });
      handler.postDelayed(this,15000);
    }
  };
  @Override public void onCreate(){
    super.onCreate();
    icon=BitmapFactory.decodeResource(getResources(),R.mipmap.ic_launcher);
    session=new MediaSession(this,"RadioSchipluidenAuto");
    session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS|MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
    session.setCallback(new MediaSession.Callback(){
      @Override public void onPlay(){sendCommand(true);}
      @Override public void onPause(){sendCommand(false);}
      @Override public void onStop(){sendCommand(false);}
      @Override public void onPlayFromMediaId(String id,Bundle extras){if(STATION.equals(id))sendCommand(true);}
      @Override public void onPlayFromSearch(String query,Bundle extras){sendCommand(true);}
    });
    session.setActive(true);
    setSessionToken(session.getSessionToken());
    showMetadata();
    setState(RadioService.currentState());
    IntentFilter filter=new IntentFilter(RadioService.ACTION_STATE);
    if(Build.VERSION.SDK_INT>=33)registerReceiver(updates,filter,Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(updates,filter);
    registered=true;handler.post(poll);
  }
  private void sendCommand(boolean play){
    Intent intent=new Intent(this,RadioService.class)
      .setAction(play?RadioService.ACTION_PLAY:RadioService.ACTION_STOP);
    try{
      if(play && Build.VERSION.SDK_INT>=26)startForegroundService(intent);
      else startService(intent);
      setState(play?"loading":"stopped");
    }catch(RuntimeException ex){setState("error");}
  }
  @Override public BrowserRoot onGetRoot(String pkg,int uid,Bundle hints){
    return new BrowserRoot(ROOT,null);
  }
  @Override public void onLoadChildren(String parent,Result<List<MediaBrowser.MediaItem>> result){
    if(!ROOT.equals(parent)){result.sendResult(Collections.emptyList());return;}
    MediaDescription d=new MediaDescription.Builder()
      .setMediaId(STATION).setTitle("Radio Schipluiden")
      .setSubtitle("Luister live")
      .setIconBitmap(icon)
      .setIconUri(Uri.parse("android.resource://"+getPackageName()+"/"+R.mipmap.ic_launcher))
      .build();
    result.sendResult(Collections.singletonList(
      new MediaBrowser.MediaItem(d,MediaBrowser.MediaItem.FLAG_PLAYABLE)));
  }
  private void setState(String s){
    if(session==null)return;
    int state=PlaybackState.STATE_STOPPED;float speed=0f;
    if("playing".equals(s)){state=PlaybackState.STATE_PLAYING;speed=1f;}
    else if("loading".equals(s)){state=PlaybackState.STATE_BUFFERING;}
    else if("error".equals(s)){state=PlaybackState.STATE_ERROR;}
    session.setPlaybackState(new PlaybackState.Builder()
      .setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE
        |PlaybackState.ACTION_STOP|PlaybackState.ACTION_PLAY_PAUSE
        |PlaybackState.ACTION_PLAY_FROM_MEDIA_ID)
      .setState(state,PlaybackState.PLAYBACK_POSITION_UNKNOWN,speed).build());
  }
  private void showMetadata(){
    if(session==null)return;
    MediaMetadata.Builder m=new MediaMetadata.Builder()
      .putString(MediaMetadata.METADATA_KEY_MEDIA_ID,STATION)
      .putString(MediaMetadata.METADATA_KEY_TITLE,title)
      .putString(MediaMetadata.METADATA_KEY_ARTIST,artist)
      .putString(MediaMetadata.METADATA_KEY_ALBUM,"Radio Schipluiden · Live")
      .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE,title)
      .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,artist);
    if(icon!=null){
      m.putBitmap(MediaMetadata.METADATA_KEY_ART,icon);
      m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,icon);
    }
    session.setMetadata(m.build());
  }
  private String fetchTitle(){
    HttpURLConnection c=null;
    try{
      c=(HttpURLConnection)new URL("https://c40.radioboss.fm:8298/status-json.xsl").openConnection();
      c.setConnectTimeout(5000);c.setReadTimeout(5000);
      if(c.getResponseCode()!=200)return "";
      try(InputStream stream=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] bytes=new byte[2048];int n;
        while((n=stream.read(bytes))!=-1&&out.size()<100000)out.write(bytes,0,n);
        JSONObject stats=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8))
            .getJSONObject("icestats");
        Object src=stats.opt("source");
        if(src instanceof JSONArray){
          JSONArray all=(JSONArray)src;JSONObject candidate=null;
          for(int i=0;i<all.length();i++){
            JSONObject next=all.optJSONObject(i);if(next==null)continue;
            if(candidate==null)candidate=next;
            if(next.optString("mount").contains("stream")){candidate=next;break;}
          }
          src=candidate;
        }
        if(src instanceof JSONObject){
          String name=((JSONObject)src).optString("title","").trim();
          if(name.length()>2&&name.length()<241)return name;
        }
      }
    }catch(Exception ignored){}finally{if(c!=null)c.disconnect();}
    return "";
  }
  @Override public void onDestroy(){
    destroyed=true;handler.removeCallbacks(poll);
    if(registered)unregisterReceiver(updates);
    executor.shutdownNow();
    if(session!=null){session.setActive(false);session.release();}
    if(icon!=null)icon.recycle();
    super.onDestroy();
  }
}