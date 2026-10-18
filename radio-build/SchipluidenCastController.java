package nl.radioschipluiden.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.webkit.WebView;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import androidx.mediarouter.app.MediaRouteButton;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

/** Google Cast sender controller shared by the phone app; Android Auto remains a media browser. */
public final class SchipluidenCastController {
    private static final String STREAM="https://c40.radioboss.fm:8298/stream";
    private final Activity activity;
    private final MediaRouteButton button;
    private final SessionManager manager;
    private boolean listening=false;
    private boolean connecting=false;
    private final SessionManagerListener<CastSession> events=new SessionManagerListener<CastSession>(){
        @Override public void onSessionStarting(CastSession s){}
        @Override public void onSessionStarted(CastSession s,String id){loadStream(s);}
        @Override public void onSessionStartFailed(CastSession s,int error){connecting=false;}
        @Override public void onSessionEnding(CastSession s){}
        @Override public void onSessionEnded(CastSession s,int error){connecting=false;}
        @Override public void onSessionResuming(CastSession s,String id){}
        @Override public void onSessionResumed(CastSession s,boolean wasSuspended){connecting=false;}
        @Override public void onSessionResumeFailed(CastSession s,int error){connecting=false;}
        @Override public void onSessionSuspended(CastSession s,int reason){}
    };
    public SchipluidenCastController(Activity activity,FrameLayout root){
        this.activity=activity;
        CastContext context=CastContext.getSharedInstance(activity);
        manager=context.getSessionManager();
        int density=(int)(activity.getResources().getDisplayMetrics().density*1);
        FrameLayout chrome=new FrameLayout(activity);
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(Color.rgb(20,65,53));
        bg.setCornerRadius(14*density);
        chrome.setBackground(bg);
        FrameLayout.LayoutParams holder=new FrameLayout.LayoutParams(132*density,52*density);
        holder.gravity=android.view.Gravity.TOP|android.view.Gravity.RIGHT;
        holder.topMargin=10*density;holder.rightMargin=10*density;
        root.addView(chrome,holder);
        TextView label=new TextView(activity);
        label.setText("Cast");
        label.setTextColor(Color.WHITE);
        label.setTextSize(15);
        label.setTypeface(null, Typeface.BOLD);
        FrameLayout.LayoutParams tl=new FrameLayout.LayoutParams(72*density,52*density);
        tl.gravity=android.view.Gravity.LEFT|android.view.Gravity.CENTER_VERTICAL;
        label.setGravity(android.view.Gravity.CENTER);
        chrome.addView(label,tl);
        button=new MediaRouteButton(activity);
        button.setContentDescription("Cast Radio Schipluiden naar Google Home of Chromecast");
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(56*density,52*density);
        bp.gravity=android.view.Gravity.RIGHT|android.view.Gravity.CENTER_VERTICAL;
        chrome.addView(button,bp);
        CastButtonFactory.setUpMediaRouteButton(activity,button);
    }
    public void onStart(){
        if(!listening){manager.addSessionManagerListener(events,CastSession.class);listening=true;}
    }
    public void onStop(){
        if(listening){manager.removeSessionManagerListener(events,CastSession.class);listening=false;}
    }
    public boolean isCasting(){
        CastSession s=manager.getCurrentCastSession();
        return s!=null && s.isConnected();
    }
    public void startOrResume(){
        CastSession s=manager.getCurrentCastSession();
        if(s==null || !s.isConnected())return;
        RemoteMediaClient c=s.getRemoteMediaClient();
        if(c!=null&&c.getMediaInfo()!=null)c.play();
        else loadStream(s);
    }
    public void stopRemote(){
        CastSession s=manager.getCurrentCastSession();
        if(s!=null&&s.isConnected()&&s.getRemoteMediaClient()!=null)
            s.getRemoteMediaClient().pause();
    }
    public void updateNowPlaying(String song){
        // The default receiver handles live radio independently; do not reload on each title change.
    }
    private void loadStream(CastSession session){
        if(connecting)return;
        RemoteMediaClient remote=session.getRemoteMediaClient();
        if(remote==null)return;
        connecting=true;
        MediaMetadata meta=new MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);
        meta.putString(MediaMetadata.KEY_TITLE,"Radio Schipluiden – Live");
        meta.putString(MediaMetadata.KEY_ARTIST,"Radio Schipluiden");
        MediaInfo media=new MediaInfo.Builder(STREAM)
            .setContentType("audio/mpeg")
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setMetadata(meta).build();
        MediaLoadRequestData request=new MediaLoadRequestData.Builder()
            .setMediaInfo(media).setAutoplay(true).build();
        remote.load(request).setResultCallback(result -> activity.runOnUiThread(()->{
            connecting=false;
            if(result.getStatus().isSuccess()){
                Intent stop=new Intent(activity,RadioService.class);
                stop.setAction(RadioService.ACTION_STOP);
                activity.startService(stop);
            }
        }));
    }
}