package nl.radioschipluiden.app;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.*;
import android.net.Uri;
public final class MainActivity extends Activity {
  private Button toggle; private boolean playing=false;
  @Override public void onCreate(Bundle b){super.onCreate(b);
    LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setGravity(Gravity.CENTER);root.setPadding(32,32,32,32);root.setBackgroundColor(Color.rgb(21,41,49));
    ImageView icon=new ImageView(this);icon.setImageResource(R.drawable.logo);root.addView(icon,new LinearLayout.LayoutParams(240,240));
    TextView name=new TextView(this);name.setText("RADIO SCHIPLUIDEN");name.setTextColor(Color.WHITE);name.setTextSize(29);name.setTypeface(null,Typeface.BOLD);name.setGravity(Gravity.CENTER);root.addView(name);
    TextView tagline=new TextView(this);tagline.setText("Altijd aan • Live luisteren");tagline.setTextColor(0xffffb96d);tagline.setTextSize(19);tagline.setPadding(0,12,0,36);tagline.setGravity(Gravity.CENTER);root.addView(tagline);
    toggle=new Button(this);toggle.setText("▶  LUISTER LIVE");root.addView(toggle,new LinearLayout.LayoutParams(-1,130));
    toggle.setOnClickListener(v->{ if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},21);
       Intent i=new Intent(this,RadioService.class);i.setAction(playing?"STOP":"PLAY");
       if(playing)startService(i);else startForegroundService(i);
       playing=!playing;toggle.setText(playing?"■  STOP RADIO":"▶  LUISTER LIVE");
    });
    Button site=new Button(this);site.setText("OPEN RADIO SCHIPLUIDEN WEBSITE");site.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://radioschipluiden.nl"))));root.addView(site,new LinearLayout.LayoutParams(-1,120));
    TextView note=new TextView(this);note.setText("Speelt ook door als het scherm vergrendeld is.");note.setTextColor(Color.WHITE);note.setGravity(Gravity.CENTER);root.addView(note);
    setContentView(root);
  }
}