package android.content;
public abstract class Context { public static final String WINDOW_SERVICE="",MEDIA_PROJECTION_SERVICE="",NOTIFICATION_SERVICE=""; public static final int MODE_PRIVATE=0;
 public abstract Object getSystemService(String n); public abstract android.content.res.Resources getResources(); public abstract SharedPreferences getSharedPreferences(String n,int m);
 public String getPackageName() { throw null; } public ComponentName startService(Intent i) { throw null; } public ComponentName startForegroundService(Intent i) { throw null; } public int checkSelfPermission(String p) { throw null; } }
