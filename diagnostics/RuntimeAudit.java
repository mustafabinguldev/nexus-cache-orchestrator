import network.darkland.NexusApplication;
import network.darkland.protocol.*;
import network.darkland.protocol.backup.annotations.DbDataModels;
import network.darkland.redis.*;
import network.darkland.model.DataModel;
import network.darkland.redis.security.NexusSecurityConfig;
import redis.clients.jedis.*;
import redis.clients.jedis.resps.StreamEntry;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Regression tests against dedicated Redis :16379 and MongoDB :17017. Exit 1 on failure. */
public class RuntimeAudit {
    static int checks;
    static final String RUN="audit_"+UUID.randomUUID().toString().replace("-", "");
    public static class Addon extends DataAddon {
        @DbDataModels(isId=true) public String id;
        @DbDataModels(isId=false, defaultValue="0") public long balance;
        final boolean l1;
        final int protocol;
        int ttl=300;
        Addon(boolean l1,int protocol) { this.l1=l1; this.protocol=protocol; }
        public boolean handleRequest(String s,RequestType t,NexusJsonDataContainer j) { return true; }
        public int addonId() { return protocol; }
        public String addonName() { return "RuntimeAudit"; }
        public String cacheKeyHeaderTag() { return RUN+protocol; }
        public String getDatabase() { return RUN; }
        public String getCollection() { return "models"+protocol; }
        public int getCacheTTL() { return ttl; }
        public boolean l1CacheEnabled() { return l1; }
        void custom(RequestType t,RequestHandler h) { registerHandler(t,h); }
    }
    static void await(String label,BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(!condition.getAsBoolean()) {
            if(System.nanoTime()>end) throw new AssertionError("Timeout: "+label);
            Thread.sleep(20);
        }
    }
    static void check(String label,boolean ok) {
        if(!ok) throw new AssertionError(label);
        System.out.println("AUDIT PASS "+label); checks++;
    }
    static long balance(String json) { return new JSONObject(json).getLong("balance"); }
    static String data(String id,long value) { return new JSONObject().put("id",id).put("balance",value).toString(); }
    static String packet(Addon addon,String type,JSONObject data) {
        return MessageAuth.stamp(new JSONObject().put("protocol",addon.addonId()).put("source","audit")
                .put("type",type).put("data",data).toString());
    }
    static StreamEntryID send(Jedis redis,String payload) {
        return redis.xadd(RedisManager.STREAM_KEY,StreamEntryID.NEW_ENTRY,Map.of("payload",payload));
    }
    static boolean deleted(Jedis redis,StreamEntryID id) { return redis.xrange(RedisManager.STREAM_KEY,id,id).isEmpty(); }
    static void flush(NexusApplication app) throws Exception {
        var method=RedisDataContainer.class.getDeclaredMethod("startAutoFlushTask");
        method.setAccessible(true); method.invoke(app.getDataContainer());
    }
    static void redeliver(NexusApplication app,Jedis redis,StreamEntryID id) throws Exception {
        // Exercise XAUTOCLAIM's enqueue path without waiting for its 30-second idle threshold.
        var field=RedisManager.class.getDeclaredField("inFlight"); field.setAccessible(true);
        await("worker released delivery",() -> { try { return !((Set<?>)field.get(app.getRedisManager())).contains(id); } catch(Exception e) { throw new RuntimeException(e); } });
        var method=RedisManager.class.getDeclaredMethod("enqueueEntry",StreamEntry.class); method.setAccessible(true);
        method.invoke(app.getRedisManager(),redis.xrange(RedisManager.STREAM_KEY,id,id).getFirst());
    }
    public static void main(String[] args) {
        try {
            if(!NexusSecurityConfig.isSigningEnabled()) throw new IllegalStateException("Set a disposable NEXUS_SIGNING_KEY in the test process");
            NexusApplication app=new NexusApplication("127.0.0.1",16379,null,null,"mongodb://127.0.0.1:17017",false,null,null,null,null);
            Addon normal=new Addon(true,91001), noL1=new Addon(false,91002), expiring=new Addon(true,91003);
            expiring.ttl=1;
            for(Addon a:List.of(normal,noL1,expiring)) app.getProtocolHandler().registerAddon(a);
            try(Jedis redis=new Jedis("127.0.0.1",16379)) {
                await("consumer group",() -> { try { return !redis.xinfoGroups(RedisManager.STREAM_KEY).isEmpty(); } catch(Exception e) { return false; } });
                app.getMongoManager().setValue(normal,"baseline",data("baseline",100)).join();
                DataModel baseline=normal.getData(new NexusJsonDataContainer("{\"id\":\"baseline\"}")).orElseThrow();
                check("MongoDB write/read and cache loading",balance(baseline.getValueJson())==100);

                Object first=normal.acquireKeyLock("same"), waiting=normal.acquireKeyLock("same");
                normal.releaseKeyLock("same",first);
                Object newcomer=normal.acquireKeyLock("same");
                CountDownLatch holding=new CountDownLatch(1), release=new CountDownLatch(1), entered=new CountDownLatch(1);
                Thread holder=Thread.ofPlatform().start(() -> { synchronized(waiting) { holding.countDown(); try { release.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } } });
                holding.await();
                Thread contender=Thread.ofPlatform().start(() -> { synchronized(newcomer) { entered.countDown(); } });
                boolean blocked;
                try { blocked=!entered.await(200,TimeUnit.MILLISECONDS); }
                finally { release.countDown(); holder.join(); contender.join(); normal.releaseKeyLock("same",waiting); normal.releaseKeyLock("same",newcomer); }
                check("same-key lock remains exclusive with waiting users",blocked && waiting==newcomer);

                for(Addon addon:List.of(noL1,expiring)) {
                    app.getMongoManager().setValue(addon,"player",data("player",100)).join();
                    DataModel model=addon.getData(new NexusJsonDataContainer("{\"id\":\"player\"}")).orElseThrow();
                    model.setValueJson(data("player",150));
                    app.getRedisManager().setData(model.getKey(),model.getValueJson(),addon);
                    if(addon==expiring) await("cache expiration",() -> app.getDataContainer().getDataModelFromKey(model.getKey()).isEmpty() && redis.get(model.getKey())==null);
                    flush(app);
                    await("pending write persisted",() -> !app.getDataContainer().getDirtyKeys().contains(model.getKey()));
                    check(addon==noL1?"L1 disabled: MongoDB balance=150":"expired cache: MongoDB balance=150",balance(app.getMongoManager().getValue(addon,"player").join())==150);
                }

                RequestType slow=RequestType.of("AUDIT_SLOW");
                CountDownLatch started=new CountDownLatch(1), finish=new CountDownLatch(1), done=new CountDownLatch(1);
                normal.custom(slow,(a,s,j) -> app.getRedisManager().processTask(() -> {
                    started.countDown();
                    try { if(!finish.await(8,TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                    catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                    finally { done.countDown(); }
                }));
                StreamEntryID message=send(redis,packet(normal,"AUDIT_SLOW",new JSONObject()));
                try {
                    check("signed message dispatched",started.await(5,TimeUnit.SECONDS));
                    Thread.sleep(200);
                    check("ACK waits for nested asynchronous task",!deleted(redis,message) && done.getCount()==1);
                } finally { finish.countDown(); }
                await("ACK after completion",() -> deleted(redis,message));
                check("completed message acknowledged",done.getCount()==0);

                AtomicInteger unauthorized=new AtomicInteger();
                RequestType security=RequestType.of("AUDIT_SECURITY");
                normal.custom(security,(a,s,j) -> unauthorized.incrementAndGet());
                JSONObject unsigned=new JSONObject(packet(normal,"AUDIT_SECURITY",new JSONObject())); unsigned.remove("sig");
                StreamEntryID invalid=send(redis,unsigned.toString());
                await("unsigned rejection",() -> deleted(redis,invalid));
                check("unsigned message rejected with signing enabled",unauthorized.get()==0);
                JSONObject tampered=new JSONObject(packet(normal,"AUDIT_SECURITY",new JSONObject())); tampered.put("source","tampered");
                StreamEntryID altered=send(redis,tampered.toString());
                await("tampered rejection",() -> deleted(redis,altered));
                check("tampered signature rejected",unauthorized.get()==0);

                AtomicInteger attempts=new AtomicInteger();
                RequestType retry=RequestType.of("AUDIT_RETRY");
                normal.custom(retry,(a,s,j) -> app.getRedisManager().processTask(() -> {
                    if(attempts.incrementAndGet()==1) throw new IllegalStateException("injected failure before mutation");
                }));
                String retryPacket=packet(normal,"AUDIT_RETRY",new JSONObject());
                StreamEntryID failed=send(redis,retryPacket);
                await("failed attempt",() -> attempts.get()==1);
                Thread.sleep(100);
                check("failed task remains in Redis pending list",!deleted(redis,failed));
                redeliver(app,redis,failed);
                await("retry completed",() -> deleted(redis,failed));
                check("same delivery retries without nonce rejection",attempts.get()==2);
                StreamEntryID replay=send(redis,retryPacket);
                await("replay rejected",() -> deleted(redis,replay));
                check("same nonce in a different delivery is rejected",attempts.get()==2);

                for(Addon addon:List.of(normal,noL1)) {
                    StreamEntryID create=send(redis,packet(addon,"SET_DATA",new JSONObject(data("counter",0))));
                    await("SET completed",() -> deleted(redis,create));
                    check("SET persisted before ACK, L1="+addon.l1,balance(app.getMongoManager().getValue(addon,"counter").join())==0);
                    List<StreamEntryID> messages=new ArrayList<>();
                    for(int i=0;i<50;i++) messages.add(send(redis,packet(addon,"INCREMENT_DATA",new JSONObject().put("key","counter").put("field","balance").put("amount",1))));
                    await("concurrent increments",() -> messages.stream().allMatch(id -> deleted(redis,id)));
                    check("50 concurrent increments persist exactly 50, L1="+addon.l1,balance(app.getMongoManager().getValue(addon,"counter").join())==50 && balance(redis.get(addon.cacheKeyHeaderTag()+"_counter"))==50);
                }

                app.getMongoManager().setValue(normal,"readFailure",data("readFailure",777)).join();
                app.getRedisManager().getResilience().mongoCircuitBreaker().transitionToOpenState();
                StreamEntryID blockedRead=send(redis,packet(normal,"GET_DATA",new JSONObject().put("id","readFailure")));
                Thread.sleep(300);
                check("Mongo read failure does not create a default model",redis.get(normal.cacheKeyHeaderTag()+"_readFailure")==null && !deleted(redis,blockedRead));
                app.getRedisManager().getResilience().mongoCircuitBreaker().reset();
                redeliver(app,redis,blockedRead);
                await("Mongo read recovery",() -> deleted(redis,blockedRead));
                check("Mongo recovery retains original balance=777",balance(app.getMongoManager().getValue(normal,"readFailure").join())==777);

                app.getRedisManager().getResilience().mongoCircuitBreaker().transitionToOpenState();
                StreamEntryID failedFlush=send(redis,packet(normal,"INCREMENT_DATA",new JSONObject().put("key","baseline").put("field","balance").put("amount",1)));
                var completedField=RedisManager.class.getDeclaredField("completedTasks"); completedField.setAccessible(true);
                await("increment applied before failing flush",() -> { try { return ((Map<?,?>)completedField.get(app.getRedisManager())).containsKey(failedFlush); } catch(Exception e) { throw new RuntimeException(e); } });
                check("failed Mongo flush does not ACK increment",!deleted(redis,failedFlush));
                app.getRedisManager().getResilience().mongoCircuitBreaker().reset();
                redeliver(app,redis,failedFlush);
                await("flush retry",() -> deleted(redis,failedFlush));
                check("flush retry does not apply increment twice",balance(app.getMongoManager().getValue(normal,"baseline").join())==101);

                BlockingQueue<String> responses=new LinkedBlockingQueue<>();
                CountDownLatch subscribed=new CountDownLatch(1);
                JedisPubSub listener=new JedisPubSub() {
                    public void onSubscribe(String channel,int count) { subscribed.countDown(); }
                    public void onMessage(String channel,String value) { responses.add(value); }
                };
                Thread subscriber=Thread.ofPlatform().daemon().start(() -> {
                    try(Jedis connection=new Jedis("127.0.0.1",16379)) { connection.subscribe(listener,RedisManager.CHANNEL+"_audit"); }
                });
                try {
                    if(!subscribed.await(5,TimeUnit.SECONDS)) throw new AssertionError("Pub/Sub subscription failed");
                    for(String type:List.of("GET_DATA","RANKING","RANK_FINDER")) {
                        JSONObject request=type.equals("GET_DATA")?new JSONObject().put("id","baseline"):
                                new JSONObject().put("key","baseline").put("field","balance").put("order","DESC").put("limit",3);
                        StreamEntryID query=send(redis,packet(normal,type,request));
                        String response=responses.poll(5,TimeUnit.SECONDS);
                        check(type+" Pub/Sub response received",response!=null);
                        JSONObject body=new JSONObject(response);
                        check(type+" response type preserved",body.getString("type").equals(type.equals("GET_DATA")?"BROADCAST":type+"_RESPONSE"));
                        await(type+" ACK",() -> deleted(redis,query));
                    }
                } finally { listener.unsubscribe(); subscriber.join(2000); }

                StreamEntryID remove=send(redis,packet(normal,"REMOVE_DATA",new JSONObject().put("id","counter").put("all",true)));
                await("REMOVE completed",() -> deleted(redis,remove));
                check("REMOVE clears Redis and MongoDB",redis.get(normal.cacheKeyHeaderTag()+"_counter")==null && app.getMongoManager().getValue(normal,"counter").join()==null);
            }
            System.out.println("AUDIT SUMMARY checks="+checks+" database="+RUN);
            System.exit(0);
        } catch(Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}
