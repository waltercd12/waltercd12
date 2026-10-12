package nl.radioschipluiden.app;
import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
public final class RadioService extends Service {
 private MediaPlayer player;private final String stream="https://c40.radioboss.fm:8298/stream";
 @Override public void onCreate(){super.onCreate();NotificationManager n=getSystemService(NotificationManager.class);n.createNotificationChannel(new NotificationChannel("radio_live","Radio Schipluiden Live",NotificationManager.IMPORTANCE_LOW));}
 @Override public int onStartCommand(Intent intent,int flags,int id){
 if(intent!=null && "STOP".equals(intent.getAction())){stopPlay();stopSelf();return START_NOT_STICKY;}
 Intent open=new Intent(this,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
 Intent stop=new Intent(this,RadioService.class).setAction("STOP");PendingIntent sp=PendingIntent.getService(this,1,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
 Notification notification=new Notification.Builder(this,"radio_live").setSmallIcon(R.drawable.logo).setContentTitle("Radio Schipluiden").setContentText("Live radio wordt afgespeeld").setContentIntent(pi).addAction(android.R.drawable.ic_media_pause,"Stop",sp).setOngoing(true).build();
 if(Build.VERSION.SDK_INT>=29) startForeground(42,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);else startForeground(42,notification);
 if(player==null){ try{player=new MediaPlayer();player.setDataSource(this,Uri.parse(stream));player.setOnPreparedListener(MediaPlayer::start);player.setOnErrorListener((mp,w,e)->{stopPlay();stopSelf();return true;});player.prepareAsync();}catch(Exception e){stopPlay();stopSelf();}}
 return START_NOT_STICKY;
 }
 private void stopPlay(){if(player!=null){try{player.stop();}catch(Exception ignored){}player.release();player=null;}stopForeground(STOP_FOREGROUND_REMOVE);}
 @Override public void onDestroy(){stopPlay();super.onDestroy();}
 @Override public IBinder onBind(Intent i){return null;}
}