package nl.radioschipluiden.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;

public final class RadioService extends Service {
    public static final String PLAY="nl.radioschipluiden.app.PLAY", STOP="nl.radioschipluiden.app.STOP", EVENT="nl.radioschipluiden.app.STATE";
    private static final String[] STREAMS={"https://c40.radioboss.fm:8298/stream","https://c40.radioboss.fm/stream/298"};
    private final Handler handler=new Handler(Looper.getMainLooper());
    public static volatile String stage="off";
    private MediaPlayer player;
    private int token=0;
    private boolean started=false;
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("radio","Radio Schipluiden ON AIR",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null && STOP.equals(intent.getAction())){shutdown();stopSelf();return START_NOT_STICKY;}
        if(!started){started=true;showNotification();playIntro();}
        return START_NOT_STICKY;
    }
    private void stage(String s){stage=s;Intent update=new Intent(EVENT).setPackage(getPackageName());update.putExtra("stage",s);sendBroadcast(update);}
    private void showNotification(){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,RadioService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,"radio").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Radio Schipluiden • ON AIR").setContentText("Radio Schipluiden – live luisteren").setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_media_pause,"Stop",stop).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(19,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);else startForeground(19,n);
    }
    private void release(){if(player!=null){player.setOnCompletionListener(null);player.setOnErrorListener(null);try{player.reset();}catch(Exception ignored){}player.release();player=null;}}
    private void playIntro(){
        int version=++token;release();stage("intro");
        try{
            player=MediaPlayer.create(this,R.raw.intro_jingle);
            if(player==null)throw new IllegalStateException("Jingle niet gevonden");
            player.setOnCompletionListener(m->{if(started && token==version)startStream(version);});
            player.setOnErrorListener((m,what,extra)->{handler.post(()->startStream(version,0));return true;});
            player.start();
        }catch(Exception ex){startStream(version,0);}
    }
    private void startStream(int version,int index){
        if(!started||version!=token)return;
        if(index>=STREAMS.length){error();return;}
        release();stage(index==0?"connecting":"alternate");
        try{
            MediaPlayer next=new MediaPlayer();player=next;
            next.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            next.setWakeMode(this,android.os.PowerManager.PARTIAL_WAKE_LOCK);
            next.setDataSource(this,Uri.parse(STREAMS[index]));
            next.setOnPreparedListener(mp->{if(started && version==token){try{mp.start();stage("playing");}catch(Exception e){startStream(version,index+1);}}});
            next.setOnErrorListener((mp,what,extra)->{handler.post(()->startStream(version,index+1));return true;});
            next.setOnInfoListener((mp,what,extra)->{if(what==MediaPlayer.MEDIA_INFO_BUFFERING_START)stage("buffering");else if(what==MediaPlayer.MEDIA_INFO_BUFFERING_END)stage("playing");return false;});
            next.prepareAsync();
            handler.postDelayed(()->{
                if(started&&version==token&&player==next){
                    try{if(!next.isPlaying())startStream(version,index+1);}
                    catch(Exception ignored){startStream(version,index+1);}
                }
            },18000);
        }catch(Exception ex){startStream(version,index+1);}
    }
    private void startStream(int version){startStream(version,0);}
    private void error(){started=false;++token;release();stopForeground(STOP_FOREGROUND_REMOVE);stage("error");stopSelf();}
    private void shutdown(){started=false;++token;release();stage("off");stopForeground(STOP_FOREGROUND_REMOVE);}
    @Override public void onDestroy(){shutdown();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
