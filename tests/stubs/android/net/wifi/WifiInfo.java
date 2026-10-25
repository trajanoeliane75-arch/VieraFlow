package android.net.wifi;
public class WifiInfo {
 public int getIpAddress(){
  String[] fields=System.getProperty("test.ip","127.0.0.1").split("\\."); int ip=0;
  for(int i=0;i<4;i++)ip|=Integer.parseInt(fields[i])<<(8*i); return ip;
 }
}
