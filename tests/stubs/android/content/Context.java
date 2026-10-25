package android.content;
public class Context {
 public static final String WIFI_SERVICE="wifi";
 public java.io.File getCacheDir(){return new java.io.File(System.getProperty("java.io.tmpdir"));}
 public Context getApplicationContext(){return this;}
 public Object getSystemService(String service){return new android.net.wifi.WifiManager();}
}
