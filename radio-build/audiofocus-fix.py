from pathlib import Path
p=Path("app/src/main/java/nl/radioschipluiden/app/RadioService.java")
s=p.read_text()
def repl(before,after):
    global s
    assert before in s, "Missing anchor: "+before[:80]
    s=s.replace(before,after,1)
repl("import android.media.MediaPlayer;","import android.media.MediaPlayer;\nimport android.media.AudioManager;\nimport android.media.AudioFocusRequest;")
repl("    private MediaPlayer player;", """    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean focusHeld, readyToPlay;
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> handler.post(() -> {
        if (!wantsPlayback) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            focusHeld = true;
            handler.removeCallbacks(focusRetry);
            if (player == null) connect(); else startPrepared();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS) {
            focusHeld = false;
            if (player != null && readyToPlay) {
                try { if (player.isPlaying()) player.pause(); } catch (RuntimeException ignored) { }
            }
            publish("loading", "Android Auto heeft het geluid tijdelijk gepauzeerd");
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (player != null) try { player.setVolume(0.25f, 0.25f); } catch (RuntimeException ignored) { }
        }
    });
    private final Runnable focusRetry = new Runnable() {
        @Override public void run() { if (wantsPlayback && !focusHeld) {
            if (requestAudioFocus() && player == null) connect();
            else if (focusHeld) startPrepared();
        }}
    };
    private boolean requestAudioFocus() {
        if (!wantsPlayback) return false;
        if (focusHeld) return true;
        if (audioManager == null) audioManager=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return false;
        try {
            int result;
            if (Build.VERSION.SDK_INT >= 26) {
                if (audioFocusRequest == null) {
                    audioFocusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAcceptsDelayedFocusGain(true)
                        .setOnAudioFocusChangeListener(focusListener, handler).build();
                }
                result=audioManager.requestAudioFocus(audioFocusRequest);
            } else {
                result=audioManager.requestAudioFocus(focusListener,
                    AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);
            }
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                focusHeld=true;
                handler.removeCallbacks(focusRetry);
                return true;
            }
            if (result != AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
                handler.postDelayed(focusRetry, 3000L);
            publish("loading", "Wachten op geluidsuitvoer van Android Auto…");
        } catch (RuntimeException e) {
            handler.removeCallbacks(focusRetry);
            handler.postDelayed(focusRetry, 3000L);
        }
        return false;
    }
    private void abandonAudioFocus() {
        handler.removeCallbacks(focusRetry);
        if (audioManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= 26 && audioFocusRequest != null)
                    audioManager.abandonAudioFocusRequest(audioFocusRequest);
                else audioManager.abandonAudioFocus(focusListener);
            } catch (RuntimeException ignored) { }
        }
        focusHeld=false;
    }
    private void startPrepared() {
        if (!wantsPlayback || !focusHeld || !readyToPlay || player==null) return;
        try {
            player.setVolume(1f,1f);
            if (!player.isPlaying()) player.start();
            connectingAt=bufferingAt=0;
            retryCount=0;
            publish("playing", "Radio Schipluiden speelt via Android Auto");
            updateNotification("Live radio speelt");
        } catch (RuntimeException e) { reconnect("De stream startte niet. Opnieuw verbinden…"); }
    }

    private MediaPlayer player;""")
repl("        if (wantsPlayback) return START_STICKY;", """        if (wantsPlayback) {
            if (requestAudioFocus()) {
                if (player == null) connect(); else startPrepared();
            }
            return START_STICKY;
        }""")
repl("        connect();\n        return START_STICKY;", """        if (requestAudioFocus()) connect();
        return START_STICKY;""")
repl("    private void connect() {\n        if (!wantsPlayback) return;", """    private void connect() {
        if (!wantsPlayback) return;
        if (!focusHeld && !requestAudioFocus()) return;""")
repl("        releasePlayer();\n        connectingAt", "        releasePlayer();\n        readyToPlay=false;\n        connectingAt")
repl("""                try {
                    mp.start();
                    connectingAt = 0;
                    bufferingAt = 0;
                    retryCount = 0;
                    publish("playing", "Je luistert live · radio blijft op de achtergrond spelen");
                    updateNotification("Live radio speelt");
                } catch (RuntimeException e) {
                    reconnect("Radio kon niet starten. Opnieuw proberen…");
                }""", """                readyToPlay=true;
                if (focusHeld) startPrepared();
                else publish("loading", "Wachten op geluidsuitvoer van Android Auto…");""")
repl("""                    publish("playing", "Je luistert live · radio blijft op de achtergrond spelen");""", """                    if (focusHeld) publish("playing", "Radio Schipluiden speelt via Android Auto");""")
repl("    private void releasePlayer() {\n        if (player == null) return;", "    private void releasePlayer() {\n        readyToPlay=false;\n        if (player == null) return;")
repl("        handler.removeCallbacks(watchdog);\n        releasePlayer();", "        handler.removeCallbacks(watchdog);\n        abandonAudioFocus();\n        releasePlayer();")
repl("        handler.removeCallbacks(watchdog);\n        releasePlayer();", "        handler.removeCallbacks(watchdog);\n        abandonAudioFocus();\n        releasePlayer();")
# Java forbids unqualified forward field references from field initializers.
# Declare the player and play intent before the focus listener and retry callback.
s=s.replace("    private AudioManager audioManager;", "    private MediaPlayer player;\n    private boolean wantsPlayback;\n    private AudioManager audioManager;", 1)
s=s.replace("    private MediaPlayer player;\n    private NotificationManager notificationManager;", "    private NotificationManager notificationManager;", 1)
s=s.replace("    private boolean wantsPlayback;\n    private boolean inForeground;", "    private boolean inForeground;", 1)
a0=s.index("    private final AudioManager.OnAudioFocusChangeListener focusListener")
a1=s.index("    private final Runnable focusRetry", a0)
a2=s.index("    private boolean requestAudioFocus()", a1)
s=s[:a0]+s[a1:a2]+s[a0:a1]+s[a2:]
p.write_text(s)
a=Path("app/src/main/java/nl/radioschipluiden/app/AutoMediaService.java")
t=a.read_text()
old='    session.setActive(true);'
assert old in t
t=t.replace(old, '    session.setPlaybackToLocal(new AudioAttributes.Builder()\n        .setUsage(AudioAttributes.USAGE_MEDIA)\n        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());\n'+old,1)
a.write_text(t)
print("Audio focus patch applied:",len(s),"bytes; Android Auto session routed to music stream")