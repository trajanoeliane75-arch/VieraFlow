package android.net.wifi;
public class WifiManager {
 public WifiInfo getConnectionInfo(){return new WifiInfo();}
 public MulticastLock createMulticastLock(String name){return new MulticastLock();}
 public static class MulticastLock {
  boolean held; public void setReferenceCounted(boolean b){} public void acquire(){held=true;}
  public boolean isHeld(){return held;} public void release(){held=false;}
 }
}
