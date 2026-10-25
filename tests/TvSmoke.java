package br.dev.sebasty.vieraflow;
import android.content.Context;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class TvSmoke {
 static String query(String ip,String action)throws Exception{
  String type="urn:schemas-upnp-org:service:AVTransport:1";
  byte[] xml=("<s:Envelope xmlns:s='http://schemas.xmlsoap.org/soap/envelope/'><s:Body><u:"+action+" xmlns:u='"+type+"'><InstanceID>0</InstanceID></u:"+action+"></s:Body></s:Envelope>").getBytes(StandardCharsets.UTF_8);
  HttpURLConnection c=(HttpURLConnection)new URL("http://"+ip+":55000/dmr/control_2").openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setReadTimeout(10000);c.setRequestProperty("SOAPACTION","\""+type+"#"+action+"\"");c.setRequestProperty("Content-Type","text/xml; charset=utf-8");
  try{c.getOutputStream().write(xml);return new String(c.getInputStream().readAllBytes(),StandardCharsets.UTF_8);}finally{c.disconnect();}
 }
 public static void main(String[] args)throws Exception{
  String ip=args[1];String before=query(ip,"GetTransportInfo");System.out.println("BEFORE "+before);
  if(!before.contains(">STOPPED<")&&!before.contains(">NO_MEDIA_PRESENT<"))throw new Exception("TV is busy; test skipped");
  Path fixture=Paths.get(args[0]);try(java.util.stream.Stream<Path> paths=Files.list(fixture)){
   paths.filter(Files::isRegularFile).forEach(p->{try{IntegrationTest.files.put("/media/"+p.getFileName(),Files.readAllBytes(p));}catch(Exception e){throw new RuntimeException(e);}});
  }
  HttpServer server=HttpServer.create(new InetSocketAddress("0.0.0.0",0),0);server.createContext("/",IntegrationTest::handle);ExecutorService pool=Executors.newCachedThreadPool();server.setExecutor(pool);server.start();
  StreamProxy proxy=new StreamProxy(new Context(), false);DlnaController controller=new DlnaController(new Context());
  proxy.setErrorListener(e->System.out.println("PROXY ERROR "+e));
  try{
   proxy.start("","VieraFlow-TV-test");
   StreamProxy.Media media=proxy.prepare("http://127.0.0.1:"+server.getAddress().getPort()+"/media/"+(args.length>2?args[2]:"sample.m3u8"));
   System.out.println("SENDING "+media.url+" "+media.mime);
   DlnaController.Device tv=new DlnaController.Device("SEBASTY","http://"+ip+":55000/dmr/control_2","urn:schemas-upnp-org:service:AVTransport:1");
   CountDownLatch done=new CountDownLatch(1); AtomicReference<String> result=new AtomicReference<>(); controller.play(tv,media.url,"VieraFlow teste",media.mime,e->{result.set(e);done.countDown();}); if(!done.await(50,TimeUnit.SECONDS))throw new Exception("TV response timed out"); String error=result.get();System.out.println("PLAY RESULT "+error); System.out.println("ACTUAL "+query(ip,"GetTransportInfo")); System.out.println("POSITION "+query(ip,"GetPositionInfo"));
   if(error==null){
    System.out.println("POSITION 1 "+query(ip,"GetPositionInfo"));
    Thread.sleep(4000);
    System.out.println("POSITION 2 "+query(ip,"GetPositionInfo"));
    System.out.println("STATE "+query(ip,"GetTransportInfo"));
   }
  }finally{
   System.out.println("STOP "+query(ip,"Stop"));proxy.close();controller.close();server.stop(0);pool.shutdownNow();
  }
 }
}
