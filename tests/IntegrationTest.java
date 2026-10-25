package br.dev.sebasty.vieraflow;

import android.content.Context;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

public class IntegrationTest {
 static int passed;
 static final Map<String,byte[]> files=new ConcurrentHashMap<>();
 static byte[] first,second,joined;
 static AtomicInteger live=new AtomicInteger(), states=new AtomicInteger();
 static String soapMode="ok", metadata;
 static String transport="STOPPED";
 static void check(boolean value,String label){if(!value)throw new AssertionError(label);passed++;System.out.println("PASS "+label);}
 static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
 static void put(String path,String text){files.put(path,bytes(text));}
 static void expectFailure(StreamProxy proxy,String url,String label)throws Exception{
  try{proxy.prepare(url);throw new AssertionError(label+" was accepted");}
  catch(IOException e){check(true,label+": "+e.getMessage());}
 }
 static byte[] read(String url,String method)throws Exception{
  HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod(method);c.setReadTimeout(10000);
  try{check(c.getResponseCode()==200,method+" HTTP 200");return c.getInputStream().readAllBytes();}finally{c.disconnect();}
 }
 static String media(String segment){return "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:2,\n"+segment+"\n#EXT-X-ENDLIST\n";}
 static void handle(HttpExchange x)throws IOException{
  try{
   String path=x.getRequestURI().getPath();
   if(path.equals("/soap")){soap(x);return;}
   if(path.equals("/head-blocked.mp4") && x.getRequestMethod().equals("HEAD")){x.sendResponseHeaders(405,-1);return;}
   if(path.equals("/redirect")){x.getResponseHeaders().set("Location","/media/index.m3u8");x.sendResponseHeaders(302,-1);return;}
   if(path.equals("/live.m3u8")){
    String m="#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:10\n#EXTINF:2,\n/media/first.ts\n";
    if(live.incrementAndGet()>2)m+="#EXTINF:2,\n/media/second.ts\n#EXT-X-ENDLIST\n";
    files.put(path,bytes(m));
   }
   byte[] body=files.get(path);
   if(body==null){x.sendResponseHeaders(404,-1);return;}
   if(path.equals("/protected.m3u8") && (!"test_session=1".equals(x.getRequestHeaders().getFirst("Cookie")) || !"https://example.org/page".equals(x.getRequestHeaders().getFirst("Referer")))){
    x.sendResponseHeaders(403,-1);return;
   }
   String type=(path.endsWith(".m3u8")||path.equals("/no-extension"))?"application/vnd.apple.mpegurl":path.endsWith(".mp4")?"video/mp4":"application/octet-stream";
   x.getResponseHeaders().set("Content-Type",type);
   String range=x.getRequestHeaders().getFirst("Range");int code=200;
   if(range!=null && !path.equals("/ignore-range.ts")){
    String[] r=range.substring(6).split("-");int start=Integer.parseInt(r[0]),end=r.length>1?Math.min(body.length-1,Integer.parseInt(r[1])):body.length-1;
    if(start>=body.length){x.sendResponseHeaders(416,-1);return;}
    x.getResponseHeaders().set("Content-Range","bytes "+start+"-"+end+"/"+body.length);
    x.getResponseHeaders().set("Accept-Ranges","bytes");body=Arrays.copyOfRange(body,start,end+1);code=206;
   }
   if(x.getRequestMethod().equals("HEAD")){x.getResponseHeaders().set("Content-Length",""+body.length);x.sendResponseHeaders(code,-1);return;}
   x.sendResponseHeaders(code,body.length);x.getResponseBody().write(body);
  }finally{x.close();}
 }
 static void soap(HttpExchange x)throws IOException{
  String action=x.getRequestHeaders().getFirst("SOAPACTION");String body=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
  String response="";int status=200;
  if(action.contains("SetAVTransportURI")){
   metadata=body;
   if(soapMode.equals("704")){status=500;response="<s:Fault><detail><UPnPError><errorCode>704</errorCode><errorDescription>Format not supported for playback</errorDescription></UPnPError></detail></s:Fault>";}
  }else if(action.contains("Stop")){transport="STOPPED";
  }else if(action.contains("Play")){transport="PLAYING";states.set(0);
  }else if(action.contains("GetTransportInfo")){
   String state=transport.equals("PLAYING") && states.incrementAndGet()<2?"TRANSITIONING":transport;
   response="<u:GetTransportInfoResponse xmlns:u='urn:schemas-upnp-org:service:AVTransport:1'><CurrentTransportState>"+state+"</CurrentTransportState><CurrentTransportStatus>"+(soapMode.equals("error")?"ERROR_OCCURRED":"OK")+"</CurrentTransportStatus></u:GetTransportInfoResponse>";
  }
  byte[] data=bytes("<s:Envelope xmlns:s='http://schemas.xmlsoap.org/soap/envelope/'><s:Body>"+response+"</s:Body></s:Envelope>");
  x.getResponseHeaders().set("Content-Type","text/xml");x.sendResponseHeaders(status,data.length);x.getResponseBody().write(data);
 }
 static String play(DlnaController controller,DlnaController.Device tv,String uri)throws Exception{
  CountDownLatch latch=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();
  controller.play(tv,uri,"Teste & vídeo <1>","video/mpeg",e->{error.set(e);latch.countDown();});
  if(!latch.await(20,TimeUnit.SECONDS))throw new AssertionError("DLNA timed out");return error.get();
 }
 public static void main(String[] args)throws Exception{
  Path fixture=Paths.get(args[0]);first=Files.readAllBytes(fixture.resolve("segment00.ts"));second=Files.readAllBytes(fixture.resolve("segment01.ts"));
  joined=new byte[first.length+second.length];System.arraycopy(first,0,joined,0,first.length);System.arraycopy(second,0,joined,first.length,second.length);
  files.put("/media/first.ts",first);files.put("/media/second.ts",second);files.put("/combined.ts",joined);files.put("/ignore-range.ts",joined);
  put("/media/index.m3u8",media("first.ts"));put("/no-extension",media("/media/first.ts"));put("/protected.m3u8",media("/media/first.ts"));
  put("/chunk.m3u8","#EXTM3U\n#EXTINF:2,\n/media/first.ts\n#EXTINF:2,\n/media/second.ts\n#EXTINF:2,\n/media/first.ts\n#EXTINF:2,\n/media/second.ts\n#EXT-X-ENDLIST\n");
  put("/master.m3u8","#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=1280x720,CODECS=\"hvc1.1.6,mp4a.40.2\"\n/bad.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=640x360,CODECS=\"avc1.4d401e,mp4a.40.2\"\n/media/index.m3u8\n");
  String range="#EXTM3U\n#EXT-X-BYTERANGE:"+first.length+"@0\n/combined.ts\n#EXT-X-BYTERANGE:"+second.length+"\n/combined.ts\n#EXT-X-ENDLIST\n";
  put("/range.m3u8",range);put("/range-ignore.m3u8",range.replace("combined.ts","ignore-range.ts"));
  byte[] wrapped=new byte[first.length+77];System.arraycopy(first,0,wrapped,77,first.length);files.put("/picture.png",wrapped);put("/wrapped.m3u8",media("/picture.png"));
  byte[] key=new byte[16],iv=new byte[16];new Random(7).nextBytes(key);iv[15]=7;
  Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));
  files.put("/encrypted.ts",cipher.doFinal(first));files.put("/key",key);
  put("/aes.m3u8","#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n#EXT-X-KEY:METHOD=AES-128,URI=\"/key\"\n#EXTINF:2,\n/encrypted.ts\n#EXT-X-ENDLIST\n");
  put("/aes-iv.m3u8",new String(files.get("/aes.m3u8"),StandardCharsets.UTF_8).replace("URI=\"/key\"","URI=\"/key\",IV=0x07"));
  put("/fmp4.m3u8","#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\nfile.m4s\n#EXT-X-ENDLIST");
  put("/external.m3u8","#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=100,AUDIO=\"a\",CODECS=\"avc1.4d401e,mp4a.40.2\"\n/media/index.m3u8\n");
  put("/bad.m3u8",media("/html"));put("/html","<html>Access denied</html>");
  put("/discontinuity.m3u8",media("/media/first.ts").replace("#EXTINF","#EXT-X-DISCONTINUITY\n#EXTINF"));
  files.put("/sample.mp4",Files.readAllBytes(fixture.resolve("sample.mp4")));
  files.put("/head-blocked.mp4",files.get("/sample.mp4"));
  HttpServer server=HttpServer.create(new InetSocketAddress("0.0.0.0",0),0);server.createContext("/",IntegrationTest::handle);ExecutorService pool=Executors.newCachedThreadPool();server.setExecutor(pool);server.start();
  String origin="http://127.0.0.1:"+server.getAddress().getPort();
  HlsStream chunks=new HlsStream((url,span,limit)->new HlsStream.Resource(url,files.get(url.getPath())),new URL(origin+"/chunk.m3u8"));
  java.io.ByteArrayOutputStream firstWindow=new java.io.ByteArrayOutputStream(),secondWindow=new java.io.ByteArrayOutputStream();
  chunks.writeSegments(firstWindow,0,2);chunks.writeSegments(secondWindow,2,2);
  check(chunks.segmentCount()==4 && chunks.isComplete(),"VOD exposes bounded segments");
  check(chunks.segmentsForDuration(0,4000,60)==2,"VOD window follows playlist duration");
  check(chunks.durationMs(0,2)==4000 && chunks.durationMs(2,2)==4000 && chunks.durationMs(3,10)==2000,"chunk playing time is the sum of segment durations");
  check(Arrays.equals(joined,firstWindow.toByteArray()),"first VOD window contains only first two segments");
  check(Arrays.equals(joined,secondWindow.toByteArray()),"next VOD window contains only following two segments");
  // Regressions: the continuous VOD buffer retries transient DNS but does NOT retry signed-URL 403.
  String twoSegmentMedia="#EXTM3U\n#EXTINF:2,\n/first.ts\n#EXTINF:2,\n/second.ts\n#EXT-X-ENDLIST\n";
  AtomicInteger transientAttempts=new AtomicInteger();
  HlsStream retryStream=new HlsStream((u,span,limit)->{
   if(u.getPath().endsWith(".m3u8"))return new HlsStream.Resource(u,bytes(twoSegmentMedia));
   if(u.getPath().endsWith("first.ts"))return new HlsStream.Resource(u,first);
   if(transientAttempts.incrementAndGet()<3)throw new UnknownHostException("temporary fixture DNS error");
   return new HlsStream.Resource(u,second);
  },new URL(origin+"/retry-test.m3u8"));
  ByteArrayOutputStream recoveredStream=new ByteArrayOutputStream();retryStream.write(recoveredStream);
  check(Arrays.equals(joined,recoveredStream.toByteArray()),"continuous VOD preserves TS bytes after transient DNS failure");
  check(transientAttempts.get()==3,"temporary DNS failures are retried with bounded backoff");
  AtomicInteger expiredAttempts=new AtomicInteger();
  try{
   new HlsStream((u,span,limit)->{
    if(u.getPath().endsWith(".m3u8"))return new HlsStream.Resource(u,bytes(twoSegmentMedia));
    if(u.getPath().endsWith("first.ts"))return new HlsStream.Resource(u,first);
    expiredAttempts.incrementAndGet();throw new IOException("Fonte respondeu HTTP 403");
   },new URL(origin+"/expired-test.m3u8"));
   throw new AssertionError("403 should not be accepted");
  }catch(IOException expected){check(expiredAttempts.get()==1,"expired 403 is not repeatedly downloaded");}
  // Regression: expiring one CDN URL must not end a movie if a fresh manifest
  // gives a new address for the SAME segment and the SAME media timeline.
  AtomicInteger renewLoads=new AtomicInteger(),expiredFetches=new AtomicInteger();
  String oldList="#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\n/first.ts\n#EXTINF:2,\n/second.ts\n#EXTINF:2,\n/first.ts\n#EXTINF:2,\n/expired.ts\n#EXT-X-ENDLIST\n";
  HlsStream renew=new HlsStream((url,requestedRange,limit)->{
   String path=url.getPath();
   if(path.endsWith("fresh.m3u8"))return new HlsStream.Resource(url,bytes(renewLoads.incrementAndGet()==1?oldList:oldList.replace("/expired.ts","/renewed.ts")));
   if(path.endsWith("/first.ts"))return new HlsStream.Resource(url,first);
   if(path.endsWith("/second.ts")||path.endsWith("/renewed.ts"))return new HlsStream.Resource(url,second);
   if(path.endsWith("/expired.ts")){expiredFetches.incrementAndGet();throw new IOException("Fonte respondeu HTTP 403");}
   throw new IOException("unexpected test path");
  },new URL(origin+"/fresh.m3u8"));
  ByteArrayOutputStream rotated=new ByteArrayOutputStream();renew.write(rotated);
  check(Arrays.equals(rotated.toByteArray(),java.util.stream.Stream.of(first,second,first,second).reduce(new byte[0], (a,b)->{byte[] c=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,c,a.length,b.length);return c;})),"expired segment URL refreshed from same media playlist");
  check(renewLoads.get()==2&&expiredFetches.get()==1,"403 refresh exactly once and does not blindly retry expired URL");
  AtomicInteger sameLoads=new AtomicInteger();
  HlsStream noRenew=new HlsStream((url,requestedRange,limit)->{
   if(url.getPath().endsWith("still-expired.m3u8")){sameLoads.incrementAndGet();return new HlsStream.Resource(url,bytes(oldList));}
   if(url.getPath().endsWith("/first.ts"))return new HlsStream.Resource(url,first);
   if(url.getPath().endsWith("/second.ts"))return new HlsStream.Resource(url,second);
   throw new IOException("Fonte respondeu HTTP 403");
  },new URL(origin+"/still-expired.m3u8"));
  try{
   noRenew.write(new ByteArrayOutputStream());throw new AssertionError("expired segment should fail with diagnostic");
  }catch(IOException e){
   check(e.getMessage().contains("Segmento 4/4 em 00:00:06"),"unrecoverable error identifies segment and video time");
   check(sameLoads.get()==2,"identical refreshed playlist is not retried indefinitely");
  }
  StreamProxy proxy=new StreamProxy(new Context(), false);DlnaController controller=new DlnaController(new Context());
  try{
   proxy.start("https://example.org/page","VieraFlow-test");
   for(String path:new String[]{"/media/index.m3u8","/redirect","/no-extension","/master.m3u8","/protected.m3u8","/wrapped.m3u8","/aes.m3u8","/aes-iv.m3u8"}){
    StreamProxy.Media media=proxy.prepare(origin+path);check(media.mime.equals("video/mpeg"),path+" MIME");
    check(read(media.url,"HEAD").length==0,path+" HEAD has no body");
    check(Arrays.equals(first,read(media.url,"GET")),path+" exact MPEG-TS bytes");
   }
   for(String path:new String[]{"/range.m3u8","/range-ignore.m3u8","/live.m3u8"}){
    StreamProxy.Media media=proxy.prepare(origin+path);check(Arrays.equals(joined,read(media.url,"GET")),path+" complete ordered stream");
   }
   for(String path:new String[]{"/fmp4.m3u8","/external.m3u8","/bad.m3u8","/discontinuity.m3u8","/html"})expectFailure(proxy,origin+path,path+" rejected before TV");
   StreamProxy.Media mp4=proxy.prepare(origin+"/sample.mp4");check(mp4.mime.equals("video/mp4"),"MP4 MIME");
   StreamProxy.SourceInfo sampleInfo=proxy.inspect(origin+"/sample.mp4");
   check(sampleInfo.bytes==files.get("/sample.mp4").length,"MP4 probe reads complete file size");
   check(sampleInfo.durationMs>=11000 && sampleInfo.durationMs<=13000,"MP4 probe reads 12-second duration without full download");
   StreamProxy.Media headBlocked=proxy.prepare(origin+"/head-blocked.mp4");
   HttpURLConnection headProbe=(HttpURLConnection)new URL(headBlocked.url).openConnection();headProbe.setRequestMethod("HEAD");
   check(headProbe.getResponseCode()==200,"proxy HEAD works when origin rejects HEAD");
   check(headProbe.getContentLengthLong()==files.get("/head-blocked.mp4").length,"proxy HEAD advertises full file size");headProbe.disconnect();
   HttpURLConnection partial=(HttpURLConnection)new URL(mp4.url).openConnection();partial.setRequestProperty("Range","bytes=5-99");
   check(partial.getResponseCode()==206,"MP4 Range HTTP 206");check(partial.getInputStream().readAllBytes().length==95,"MP4 Range exact length");partial.disconnect();
   java.lang.reflect.Field cached=StreamProxy.class.getDeclaredField("preparedFiles");cached.setAccessible(true);
   @SuppressWarnings("unchecked") Map<String,File> cache=(Map<String,File>)cached.get(proxy);
   Path testFile=Files.createTempFile("vieraflow-range-test", ".mp4");Files.write(testFile,files.get("/sample.mp4"));cache.put("test.mp4",testFile.toFile());
   String local=mp4.url.substring(0,mp4.url.lastIndexOf('/')+1).replace("/media/","/file/")+"test.mp4";
   check(read(local,"HEAD").length==0,"prepared MP4 HEAD");
   HttpURLConnection suffix=(HttpURLConnection)new URL(local).openConnection();suffix.setRequestProperty("Range","bytes=-100");
   check(suffix.getResponseCode()==206,"prepared MP4 suffix range status");byte[] original=files.get("/sample.mp4");
   check(Arrays.equals(suffix.getInputStream().readAllBytes(),Arrays.copyOfRange(original,original.length-100,original.length)),"prepared MP4 suffix range exact bytes");suffix.disconnect();
   HttpURLConnection invalid=(HttpURLConnection)new URL(local).openConnection();invalid.setRequestProperty("Range","bytes=999999999-");check(invalid.getResponseCode()==416,"prepared MP4 invalid range rejected");invalid.disconnect();
   check(Navigation.target("filme ação 2026").equals("https://www.google.com/search?q=filme+a%C3%A7%C3%A3o+2026"),"search with spaces and accents");
   check(Navigation.target("example.com/movie").equals("https://example.com/movie"),"domain navigation");
   check(Navigation.target("https://example.com/?q=a").equals("https://example.com/?q=a"),"existing URL preserved");
   DlnaController.Device tv=new DlnaController.Device("mock",origin+"/soap","urn:schemas-upnp-org:service:AVTransport:1");
   check(play(controller,tv,"http://127.0.0.1/video.ts?a=1&b=2")==null,"DLNA confirmed PLAYING after TRANSITIONING");
   Document outer=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(bytes(metadata)));
   String didl=outer.getElementsByTagName("CurrentURIMetaData").item(0).getTextContent();
   Document inner=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(bytes(didl)));
   check(inner.getElementsByTagName("res").item(0).getTextContent().equals("http://127.0.0.1/video.ts?a=1&b=2"),"valid DIDL and escaped URL");
   soapMode="704";check(play(controller,tv,"http://127.0.0.1/video.ts").contains("704"),"TV 704 surfaced without false success");
   soapMode="error";check(play(controller,tv,"http://127.0.0.1/video.ts").contains("erro"),"transport error surfaced");
   StreamProxy.Media output=proxy.prepare(origin+"/range.m3u8");Files.write(fixture.resolve("relayed.ts"),read(output.url,"GET"));
   System.out.println("TOTAL "+passed+" assertions passed");
  }finally{proxy.close();controller.close();server.stop(0);pool.shutdownNow();}
 }
}
