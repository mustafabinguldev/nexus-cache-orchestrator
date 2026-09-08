import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Boots the packaged jar with temporary credentials and dedicated local test databases. */
public class WebSmokeAudit {
    static final String BASE="http://127.0.0.1:18088";
    static HttpResponse<String> call(HttpClient client,String path,String body) throws Exception {
        var req=HttpRequest.newBuilder(URI.create(BASE+path)).timeout(Duration.ofSeconds(8));
        if(body!=null) req.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(req.build(),HttpResponse.BodyHandlers.ofString());
    }
    static void check(String label,boolean ok,int status) {
        if(!ok) throw new AssertionError(label+" status="+status);
        System.out.println("WEB PASS "+label+" status="+status);
    }
    public static void main(String[] args) throws Exception {
        Path work=Path.of("web").toAbsolutePath();
        Files.createDirectories(work);
        Path jar=Path.of("../nexus-cache-orchestrato-1.6.5-boot.jar").toAbsolutePath().normalize();
        String password=UUID.randomUUID().toString();
        JSONObject config=new JSONObject().put("redisHost","127.0.0.1").put("redisPort",16379)
            .put("mongoUri","mongodb://127.0.0.1:17017").put("metricsEnabled",false)
            .put("webPort",18088).put("adminUsername","audit")
            .put("adminPasswordHash",new BCryptPasswordEncoder(12).encode(password));
        Files.writeString(work.resolve("config.json"),config.toString());
        var pb=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),
            "-jar",jar.toString(),"--server.address=127.0.0.1");
        pb.directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("application.log").toFile());
        pb.environment().put("NEXUS_CLUSTER_MODE","false");
        Process process=pb.start();
        try {
            HttpClient anon=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
            while(true) {
                if(!process.isAlive()) throw new AssertionError("Application exited "+process.exitValue()+"; see web/application.log");
                try { if(call(anon,"/api/health",null).statusCode()==200) break; } catch(java.io.IOException ignored) {}
                if(System.nanoTime()>deadline) throw new AssertionError("Startup timeout");
                Thread.sleep(200);
            }
            var health=call(anon,"/api/health",null);
            check("packaged jar health",health.statusCode()==200,health.statusCode());
            var page=call(anon,"/",null);
            check("dashboard HTML",page.statusCode()==200 && page.body().contains("<html"),page.statusCode());
            var forbidden=call(anon,"/api/stats",null);
            check("anonymous stats denied",forbidden.statusCode()==401 || forbidden.statusCode()==403,forbidden.statusCode());
            var bad=call(anon,"/api/login",new JSONObject().put("username","audit").put("password","wrong").toString());
            check("wrong password rejected",bad.statusCode()==401,bad.statusCode());
            var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
            var session=HttpClient.newBuilder().cookieHandler(cookies).build();
            var login=call(session,"/api/login",new JSONObject().put("username","audit").put("password",password).toString());
            check("valid login",login.statusCode()==200,login.statusCode());
            var stats=call(session,"/api/stats",null);
            check("authenticated stats and Mongo connectivity",stats.statusCode()==200 && new JSONObject(stats.body()).optBoolean("mongoConnected"),stats.statusCode());
            var logout=call(session,"/api/logout","{}");
            check("logout",logout.statusCode()==200,logout.statusCode());
            var after=call(session,"/api/stats",null);
            check("session invalidated",after.statusCode()==401 || after.statusCode()==403,after.statusCode());
            System.out.println("WEB SUMMARY 8 checks passed");
        } finally {
            process.destroy();
            if(!process.waitFor(10,TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); }
            Files.deleteIfExists(work.resolve("config.json"));
        }
    }
}
