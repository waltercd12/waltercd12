from pathlib import Path
main=Path('app/src/main/java/nl/radioschipluiden/app/MainActivity.java')
s=main.read_text()
assert 'extends Activity' in s
s=s.replace('import android.app.Activity;', 'import androidx.appcompat.app.AppCompatActivity;\nimport android.util.Log;')
s=s.replace('extends Activity {','extends AppCompatActivity {',1)
old='''        castController = new SchipluidenCastController(this,root);
        setContentView(root);'''
new='''        setContentView(root);
        // Cast is optional. If it fails, always display the normal radio player.
        try {
            castController = new SchipluidenCastController(this,root);
        } catch (RuntimeException | LinkageError problem) {
            castController = null;
            Log.e("RadioSchipluiden", "Optional Google Cast initialization failed", problem);
        }'''
assert old in s
s=s.replace(old,new,1)
s=s.replace('if(castController!=null)castController.onStart();', '''if(castController!=null) {
            try { castController.onStart(); }
            catch (RuntimeException | LinkageError problem) {
                Log.e("RadioSchipluiden", "Cast startup", problem);
                castController=null;
            }
        }''')
s=s.replace('if(castController!=null)castController.onStop();', '''if(castController!=null) {
            try { castController.onStop(); }
            catch (RuntimeException | LinkageError problem) { Log.w("RadioSchipluiden", "Cast cleanup", problem); }
        }''')
main.write_text(s)
style=Path('app/src/main/res/values/styles.xml')
st=style.read_text()
assert 'parent="android:style/Theme.Material.Light.NoActionBar"' in st
st=st.replace('parent="android:style/Theme.Material.Light.NoActionBar"', 'parent="Theme.AppCompat.Light.NoActionBar"')
style.write_text(st)
build=Path('app/build.gradle')
b=build.read_text()
assert 'play-services-cast-framework' in b
b=b.replace('implementation "com.google.android.gms:play-services-cast-framework:22.3.1"', 'implementation "com.google.android.gms:play-services-cast-framework:22.3.1"\n  implementation "androidx.appcompat:appcompat:1.7.0"')
b=b.replace('versionCode 11','versionCode 12').replace("versionName '2.8-GoogleCast'", "versionName '2.9-GoogleCastFix'")
assert 'versionCode 12' in b
build.write_text(b)
print('Cast crash fix compiled with AppCompatActivity + AppCompat theme + crash-safe Cast fallback')
