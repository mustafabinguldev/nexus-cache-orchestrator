package network.darkland.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import network.darkland.Influxdb.InfluxDBManager;
import network.darkland.Influxdb.annotations.NexusMetric;
import network.darkland.Influxdb.annotations.NexusMetricConfig;
import network.darkland.NexusApplication;
import network.darkland.model.DataModel;
import network.darkland.protocol.backup.annotations.DbDataModels;
import network.darkland.redis.RedisDataContainer;
import network.darkland.redis.RedisManager;
import network.darkland.util.JsonUtils;
import network.darkland.util.NexusJsonBuilder;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class DataAddon {

    protected static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final Logger LOGGER = Logger.getLogger(DataAddon.class.getName());

    // ── ID field cache ──────────────────────────────────────────────────────
    private volatile String  cachedIdFieldName = null;
    private volatile Class<?> cachedIdClass    = null;
    private final Object idCacheLock = new Object();

    // ── Annotated-fields cache ──────────────────────────────────────────────
    private volatile Field[] cachedAnnotatedFields = null;
    private final Object fieldCacheLock = new Object();

    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    // ── Abstract API ────────────────────────────────────────────────────────
    public abstract boolean handleRequest(String source, RequestType type, NexusJsonDataContainer json);
    public abstract int     addonId();
    public abstract String  addonName();
    public abstract String  cacheKeyHeaderTag();
    public abstract String  getDatabase();
    public abstract String  getCollection();
    public abstract int     getCacheTTL();

    private void releaseKeyLock(String keyValue, Object lock) {
        keyLocks.remove(keyValue, lock);
    }

    public void pushMetrics(NexusJsonDataContainer currentData) {
        NexusMetricConfig metricConfig = getClass().getAnnotation(NexusMetricConfig.class);
        if (metricConfig == null || !metricConfig.enabled()) return;

        String measurement = metricConfig.customMeasurement().isEmpty()
                ? getClass().getName()
                : metricConfig.customMeasurement();

        Point point = Point
                .measurement(measurement)
                .addTag(getIdFieldName(), getSpecificDbKeyFromJsonKeyToValue(currentData))
                .time(Instant.now(), WritePrecision.NS);

        HashMap<String, Object> fields = new HashMap<>();
        String idFieldName = getIdFieldName();

        for (Field field : getClass().getDeclaredFields()) {
            if (field.getName().equals(idFieldName)) continue;
            if (!field.isAnnotationPresent(NexusMetric.class)) continue;

            String fieldName = field.getName();
            if (!currentData.containsKey(fieldName)) continue;

            Object value      = currentData.get(fieldName, Object.class);
            NexusMetric metric = field.getAnnotation(NexusMetric.class);
            String dataKey    = metric.value().isEmpty() ? fieldName : metric.value();

            if (metric.isTag()) point.addTag(dataKey, value.toString());
            fields.put(dataKey, value);
        }

        point.addFields(fields);

        NexusApplication app = NexusApplication.getApplication();
        CompletableFuture.runAsync(() ->
                app.getInfluxDBManager().ifPresent(db -> db.write(point))
        );
    }

    // ────────────────────────────────────────────────────────────────────────
    // loadIntoCache
    // ────────────────────────────────────────────────────────────────────────
    public void loadIntoCache(Object key) {
        Class<?> expectedType = getIdClassName();
        if (expectedType == null || !expectedType.isInstance(key)) return;

        String keyTag        = cacheKeyHeaderTag() + "_" + key;
        NexusApplication app = NexusApplication.getApplication();

        if (app.getDataContainer().getDataModelFromKey(keyTag).isPresent()) return;

        NexusJsonDataContainer trigger = new NexusJsonDataContainer();
        trigger.set(getIdFieldName(), key);
        getData(trigger);
    }

    // ────────────────────────────────────────────────────────────────────────
    // handleRankFinderData
    // ────────────────────────────────────────────────────────────────────────
    public void handleRankFinderData(String source, NexusJsonDataContainer json) {
        if (!json.containsKey("field") || !json.containsKey("key") || !json.containsKey("order")) return;

        String field = json.get("field", String.class);
        String key   = json.get("key",   String.class);
        String order = json.get("order", String.class);

        NexusApplication app = NexusApplication.getApplication();
        app.getRedisManager().processTask(() ->
                app.getMongoManager().getPosition(this, key, field, order)
                        .thenAccept(position -> {
                            NexusJsonDataContainer response = new NexusJsonDataContainer();
                            response.set("protocol", addonId());
                            response.set("type",     "RANK_FINDER_RESPONSE");
                            response.set("target",   source);
                            response.set("key",      key);
                            response.set("position", position);
                            app.getRedisManager().publish(
                                    RedisManager.CHANNEL + "_" + source,
                                    response.toFullJson()
                            );
                        })
        );
    }

    // ────────────────────────────────────────────────────────────────────────
    // handleRankingData
    // ────────────────────────────────────────────────────────────────────────
    public void handleRankingData(String source, NexusJsonDataContainer json) {
        if (!json.containsKey("field") || !json.containsKey("order") || !json.containsKey("limit")) return;

        String field = json.get("field", String.class);
        String order = json.get("order", String.class);
        int    limit = json.get("limit", Integer.class);

        NexusApplication app = NexusApplication.getApplication();
        app.getRedisManager().processTask(() ->
                app.getMongoManager().getRanking(this, field, order, limit)
                        .thenAccept(rankingMap -> {
                            NexusJsonDataContainer response = new NexusJsonDataContainer();
                            response.set("protocol", addonId());
                            response.set("type",     "RANKING_RESPONSE");
                            response.set("target",   source);
                            response.set("response", rankingMap);
                            app.getRedisManager().publish(
                                    RedisManager.CHANNEL + "_" + source,
                                    response.toFullJson()
                            );
                        })
                        .exceptionally(ex -> { ex.printStackTrace(); return null; })
        );
    }

    // ────────────────────────────────────────────────────────────────────────
    // handleIncrementData
    // ────────────────────────────────────────────────────────────────────────
    public void handleIncrementData(String source, NexusJsonDataContainer json) {
        NexusApplication app = NexusApplication.getApplication();
        app.getRedisManager().processTask(() -> {
            String lockKey = null;
            Object lock = null;
            try {
                if (!json.containsKey("key") || !json.containsKey("field") || !json.containsKey("amount")) {
                    LOGGER.warning("[DataAddon/" + addonName() + "] INCREMENT_DATA: missing required fields (key/field/amount)");
                    return;
                }

                String field    = json.get("field",  String.class);
                Number amount   = json.get("amount", Number.class);
                Object keyValue = json.get("key",    getIdClassName());

                if (field == null || amount == null || keyValue == null) {
                    LOGGER.warning("[DataAddon/" + addonName() + "] INCREMENT_DATA: null value detected");
                    return;
                }

                json.set(getIdFieldName(), keyValue);

                // Per-key lock — aynı key için paralel increment'i engeller.
                lockKey = keyValue.toString();
                lock = keyLocks.computeIfAbsent(lockKey, k -> new Object());

                synchronized (lock) {
                    Optional<DataModel> dataModelOpt = getData(json);
                    if (dataModelOpt.isEmpty()) {
                        LOGGER.warning("[DataAddon/" + addonName() + "] INCREMENT_DATA: model not found, key=" + keyValue);
                        return;
                    }

                    DataModel dataModel = dataModelOpt.get();
                    JsonNode  rootNode  = MAPPER.readTree(dataModel.getValueJson());

                    if (!rootNode.has(field) || !rootNode.get(field).isNumber()) {
                        LOGGER.warning("[DataAddon/" + addonName() + "] INCREMENT_DATA: field missing or not a number -> " + field);
                        return;
                    }

                    ObjectNode updatedNode = (ObjectNode) rootNode;
                    JsonNode   targetNode  = rootNode.get(field);

                    if (targetNode.isIntegralNumber()) {
                        updatedNode.put(field, targetNode.asLong() + amount.longValue());
                    } else {
                        updatedNode.put(field, targetNode.asDouble() + amount.doubleValue());
                    }

                    String updatedJson = MAPPER.writeValueAsString(updatedNode);

                    dataModel.setValueJson(updatedJson);

                    app.getRedisManager().setData(dataModel.getKey(), updatedJson, dataModel.getAddon());

                    pushMetrics(new NexusJsonDataContainer(updatedJson));
                }

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[DataAddon/" + addonName() + "] handleIncrementData error", e);
            } finally {
                if (lockKey != null && lock != null) {
                    releaseKeyLock(lockKey, lock);
                }
            }
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    // handleRemove
    // ────────────────────────────────────────────────────────────────────────
    public void handleRemove(String source, NexusJsonDataContainer json) {
        NexusApplication app = NexusApplication.getApplication();
        app.getRedisManager().processTask(() -> {
            try {
                String idFieldName = getIdFieldName();
                if (idFieldName.isEmpty() || !json.containsKey(idFieldName)) return;
                if (!json.containsKey("all")) return;

                String  specificId = json.get(idFieldName, getIdClassName()).toString();
                boolean allRemove  = Boolean.TRUE.equals(json.get("all", Boolean.class));

                getData(json).ifPresent(dataModel -> {
                    app.getDataContainer().removeModel(dataModel.getKey());
                    if (allRemove) {
                        // Mongo'dan kalıcı silme — bloklayan bir işlem olduğu için
                        // ayrı Mongo işçi havuzunda çalıştırılır.
                        app.getRedisManager().processMongoTask(() ->
                                app.getMongoManager().removeValue(this, specificId).join()
                        );
                    }
                });

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[DataAddon/" + addonName() + "] handleRemove error", e);
            }
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    // handleGet
    // ────────────────────────────────────────────────────────────────────────
    public void handleGet(String source, NexusJsonDataContainer json) {
        NexusApplication app = NexusApplication.getApplication();
        app.getRedisManager().processTask(() -> {
            try {
                Optional<DataModel> dataModelOpt = getData(json);
                DataModel targetModel;

                if (dataModelOpt.isEmpty()) {
                    String idFieldName = getIdFieldName();
                    if (idFieldName.isEmpty()) return;

                    NexusJsonDataContainer extract = json.containsKey("data")
                            ? new NexusJsonDataContainer(MAPPER.writeValueAsString(json.get("data", Object.class)))
                            : json;

                    Object idValue = extract.get(idFieldName, Object.class);
                    if (idValue == null) return;

                    targetModel = createModel(generateRawJson(idValue.toString()));
                    app.getDataContainer().addModelDirect(targetModel.getKey(), targetModel);
                } else {
                    targetModel = dataModelOpt.get();
                }

                ObjectNode rootNode = JsonUtils.getMapper().createObjectNode();
                rootNode.put("protocol", addonId());
                rootNode.put("source",   "nexus");
                rootNode.put("type",     "BROADCAST");
                rootNode.put("target",   source);
                rootNode.set("data",     MAPPER.readTree(targetModel.getValueJson()));

                app.getRedisManager().publish(
                        RedisManager.CHANNEL + "_" + source,
                        MAPPER.writeValueAsString(rootNode)
                );

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[DataAddon/" + addonName() + "] handleGet error", e);
            }
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    // handleSet
    // ────────────────────────────────────────────────────────────────────────
    public void handleSet(String source, NexusJsonDataContainer json) {
        NexusApplication app = NexusApplication.getApplication();
        app.getRedisManager().processTask(() -> {
            try {
                String rawInput = json.containsKey("data")
                        ? JsonUtils.toJson(json.get("data", Object.class))
                        : json.toFullJson();

                if (rawInput == null || !rawInput.trim().startsWith("{")) {
                    LOGGER.warning("[DataAddon/" + addonName() + "] handleSet: geçersiz JSON, source=" + source);
                    return;
                }

                Optional<DataModel> dataModelOpt = getData(json);

                if (dataModelOpt.isEmpty()) {
                    DataModel newModel = createModel(modelInit(rawInput));
                    app.getDataContainer().addModelDirect(newModel.getKey(), newModel);
                    pushMetrics(new NexusJsonDataContainer(newModel.getValueJson()));
                } else {
                    DataModel existing = dataModelOpt.get();
                    String    updated  = modelInitComp(rawInput);

                    // setValueJson artık sadece local state + dirty flag günceller.
                    existing.setValueJson(updated);

                    // Redis'e TEK gerçek yazım burada — setData zaten TTL'i yeniliyor,
                    // ayrıca renewTTL çağırmaya gerek yok.
                    app.getRedisManager().setData(existing.getKey(), updated, existing.getAddon());
                    pushMetrics(new NexusJsonDataContainer(updated));
                }

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[DataAddon/" + addonName() + "] handleSet error", e);
            }
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    // modelInit / modelInitComp
    // ────────────────────────────────────────────────────────────────────────
    public String modelInit(String json) {
        ObjectNode outputNode = MAPPER.createObjectNode();
        try {
            JsonNode inputNode = MAPPER.readTree(json);
            for (Field field : getAnnotatedFields()) {
                String       fieldName   = field.getName();
                DbDataModels anno        = field.getAnnotation(DbDataModels.class);
                Object       targetValue = convertToType(anno.defaultValue(), field.getType());

                if (inputNode.has(fieldName) && !inputNode.get(fieldName).isNull()) {
                    targetValue = MAPPER.readerForUpdating(targetValue).readValue(inputNode.get(fieldName));
                }

                outputNode.set(fieldName,
                        targetValue != null ? MAPPER.valueToTree(targetValue) : MAPPER.createObjectNode());
            }
            return MAPPER.writeValueAsString(outputNode);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[DataAddon/" + addonName() + "] modelInit error, json=" + json, e);
            return "{}";
        }
    }

    public String modelInitComp(String json) {
        try {
            JsonNode   rootNode    = MAPPER.readTree(json);
            ObjectNode updatedNode = MAPPER.createObjectNode();

            for (Field field : getAnnotatedFields()) {
                String       fieldName = field.getName();
                DbDataModels anno      = field.getAnnotation(DbDataModels.class);
                Object       baseObj   = convertToType(anno.defaultValue(), field.getType());

                if (rootNode.has(fieldName) && !rootNode.get(fieldName).isNull()) {
                    baseObj = MAPPER.readerForUpdating(baseObj).readValue(rootNode.get(fieldName));
                }
                updatedNode.set(fieldName, MAPPER.valueToTree(baseObj));
            }
            return MAPPER.writeValueAsString(updatedNode);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[DataAddon/" + addonName() + "] modelInitComp error, json=" + json, e);
            return json;
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // getAnnotatedFields  (double-checked locking)
    // ────────────────────────────────────────────────────────────────────────
    private Field[] getAnnotatedFields() {
        if (cachedAnnotatedFields != null) return cachedAnnotatedFields;
        synchronized (fieldCacheLock) {
            if (cachedAnnotatedFields != null) return cachedAnnotatedFields;
            cachedAnnotatedFields = Arrays.stream(getClass().getDeclaredFields())
                    .filter(f -> f.isAnnotationPresent(DbDataModels.class))
                    .peek(f -> f.setAccessible(true))
                    .toArray(Field[]::new);
        }
        return cachedAnnotatedFields;
    }

    // ────────────────────────────────────────────────────────────────────────
    // convertToType
    // ────────────────────────────────────────────────────────────────────────
    private Object convertToType(String value, Class<?> type) {
        boolean blank = value == null || value.isEmpty() || value.equals("{}");

        if (blank) {
            if (type == String.class)                            return "";
            if (type == int.class     || type == Integer.class)  return 0;
            if (type == long.class    || type == Long.class)     return 0L;
            if (type == double.class  || type == Double.class)   return 0.0;
            if (type == boolean.class || type == Boolean.class)  return false;
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                try   { return MAPPER.readValue("{}", type); }
                catch (Exception ex) {
                    LOGGER.warning("[DataAddon/" + addonName() + "] convertToType: default üretilemedi -> " + type.getSimpleName());
                    return null;
                }
            }
        }

        if (type == String.class)                            return value;
        if (type == int.class     || type == Integer.class)  return Integer.parseInt(value);
        if (type == long.class    || type == Long.class)     return Long.parseLong(value);
        if (type == double.class  || type == Double.class)   return Double.parseDouble(value);
        if (type == boolean.class || type == Boolean.class)  return Boolean.parseBoolean(value);

        try {
            return MAPPER.readValue(value, type);
        } catch (Exception e) {
            LOGGER.warning("[DataAddon/" + addonName() + "] convertToType parse error: "
                    + type.getSimpleName() + " value=" + value);
            return null;
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // getIdFieldName / getIdClassName  (double-checked locking)
    // ────────────────────────────────────────────────────────────────────────
    public String getIdFieldName() {
        if (cachedIdFieldName != null) return cachedIdFieldName;
        synchronized (idCacheLock) {
            if (cachedIdFieldName != null) return cachedIdFieldName;
            for (Field f : getClass().getDeclaredFields()) {
                if (f.isAnnotationPresent(DbDataModels.class) && f.getAnnotation(DbDataModels.class).isId()) {
                    cachedIdFieldName = f.getName();
                    cachedIdClass     = f.getType();
                    return cachedIdFieldName;
                }
            }
            cachedIdFieldName = "";
        }
        return cachedIdFieldName;
    }

    public Class<?> getIdClassName() {
        if (cachedIdClass != null) return cachedIdClass;
        getIdFieldName();
        return cachedIdClass;
    }

    // ────────────────────────────────────────────────────────────────────────
    // getData
    // ────────────────────────────────────────────────────────────────────────
    public Optional<DataModel> getData(NexusJsonDataContainer dataContainer) {
        try {
            NexusJsonDataContainer work = dataContainer.containsKey("data")
                    ? new NexusJsonDataContainer(JsonUtils.toJson(dataContainer.get("data", Object.class)))
                    : dataContainer;

            String specificValue = getSpecificDbKeyFromJsonKeyToValue(work);
            if (specificValue.isEmpty() || "null".equals(specificValue)) return Optional.empty();

            String           keyTag = cacheKeyHeaderTag() + "_" + specificValue;
            NexusApplication app    = NexusApplication.getApplication();
            RedisManager     redis  = app.getRedisManager();

            // L1 — in-process cache
            Optional<DataModel> l1 = app.getDataContainer().getDataModelFromKey(keyTag);
            if (l1.isPresent()) {
                pushMetrics(new NexusJsonDataContainer(l1.get().getValueJson()));
                return l1;
            }

            // L2 — Redis cache
            // NOT: exists() + getData() arasında key expire olabilir (TOCTOU).
            // Bu yüzden ayrı bir exists() kontrolü yerine doğrudan getData()'nın
            // Optional dönüşüne güveniyoruz — race condition'ı ortadan kaldırır.
            Optional<String> redisOpt = redis.getData(keyTag);
            if (redisOpt.isPresent()) {
                String redisJson = redisOpt.get();

                if (!redisJson.trim().startsWith("{")) {
                    LOGGER.warning("[DataAddon/" + addonName() + "] Redis'te bozuk veri tespit edildi, siliniyor. key=" + keyTag);
                    redis.deleteData(keyTag);
                } else {
                    DataModel m = new DataModel(keyTag, UUID.randomUUID().toString(),
                            modelInitComp(redisJson), this, specificValue);
                    app.getDataContainer().addModelFix(keyTag, m);
                    // addModelFix -> writeToL1AndRedis zaten setData ile TTL'i yeniden
                    // ayarlıyor; burada ayrıca renewTTL çağırmaya gerek yok.
                    pushMetrics(new NexusJsonDataContainer(m.getValueJson()));
                    return Optional.of(m);
                }
            }

            // L3 — MongoDB
            String dbJson = app.getMongoManager().getValue(this, specificValue).join();
            if (dbJson != null) {
                DataModel m = new DataModel(keyTag, UUID.randomUUID().toString(),
                        modelInitComp(dbJson), this, specificValue);
                app.getDataContainer().addModel(keyTag, m);
                pushMetrics(new NexusJsonDataContainer(m.getValueJson()));
                return Optional.of(m);
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[DataAddon/" + addonName() + "] getData error", e);
        }
        return Optional.empty();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Yardımcı metodlar
    // ────────────────────────────────────────────────────────────────────────
    public String getSpecificDbKeyFromJsonKeyToValue(NexusJsonDataContainer dataContainer) {
        String idName = getIdFieldName();
        if (idName.isEmpty()) return "";
        Object val = dataContainer.get(idName, Object.class);
        return val != null ? val.toString() : "";
    }

    public String generateRawJson(String idValue) {
        String idName = getIdFieldName();
        return idName.isEmpty() ? "{}" : modelInit(NexusJsonBuilder.create().add(idName, idValue).build());
    }

    public DataModel createModel(String json) throws JsonProcessingException {
        NexusJsonDataContainer container = new NexusJsonDataContainer(json);
        String specificKey = getSpecificDbKeyFromJsonKeyToValue(container);
        String keyTag      = cacheKeyHeaderTag() + "_" + specificKey;
        return new DataModel(keyTag, UUID.randomUUID().toString(), json, this, specificKey);
    }

    // ────────────────────────────────────────────────────────────────────────
    // RequestType enum
    // ────────────────────────────────────────────────────────────────────────
    public enum RequestType {
        SET_DATA, GET_DATA, UPDATE_DATA, REMOVE_DATA,
        BROADCAST, LOAD_CACHE, INCREMENT_DATA, LIVE,
        RANKING, RANK_FINDER
    }
}