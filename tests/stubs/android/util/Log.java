package android.util;
public class Log {
 public static int i(String tag,String text){System.err.println(text);return 0;}
 public static int w(String tag,String text,Throwable e){System.err.println(text+": "+e);return 0;}
}
