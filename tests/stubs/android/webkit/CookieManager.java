package android.webkit;
public class CookieManager {
 public static CookieManager getInstance(){return new CookieManager();}
 public String getCookie(String url){return "test_session=1";}
}
