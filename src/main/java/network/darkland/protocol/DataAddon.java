package network.darkland.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import network.darkland.NexusApplication;
import network.darkland.model.DataModel;
import network.darkland.mongo.MongoManager;
import network.darkland.protocol.backup.annotations.DbDataModels;
import network.darkland.redis.RedisDataContainer;
import network.darkland.redis.RedisManager;
import network.darkland.util.JsonUtils;
import network.darkland.util.NexusJsonBuilder;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

public abstract class DataAddon {

    protected final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public abstract boolean handleRequest(String source, RequestType type, NexusJsonDataContainer json);

    public abstract int addonId();

    public abstract String addonName();


    public void loadIntoCache(Object key) {
        Class<?> expectedType = getIdClassName();
        if (expectedType == null || !expectedType.isInstance(key)) {
            return;
        }

        String specificValue = key.toString();
        String keyTag = cacheKeyHeaderTag() + "_" + specificValue;
        NexusApplication app = NexusApplication.getApplication();

        if (app.getDataContainer().getDataModelFromKey(keyTag).isPresent()) {
            return;
        }

        NexusJsonDataContainer triggerContainer =new NexusJsonDataContainer();
        triggerContainer.set(getIdFieldName(), key);
        getData(triggerContainer);
    }

    public void handleRemove(String source, NexusJsonDataContainer json)  {

        NexusApplication.getApplication().getRedisManager().processTask(() -> {
            try {
                if (json.containsKey(getIdFieldName())) {
                    String idSpesificId = json.get(getIdFieldName(), getIdClassName()).toString();
                    if (!json.containsKey("all")) {
                        return;
                    }
                    Boolean allRemove = json.get("all", Boolean.class);
                    RedisDataContainer dataContainer = NexusApplication.getApplication().getDataContainer();
                    Optional<DataModel> dataModel = getData(json);
                    if (dataModel.isPresent()) {
                        dataContainer.removeModel(dataModel.get().getKey());
                        if (allRemove) {
                            MongoManager mongoManager = NexusApplication.getApplication().getMongoManager();
                            mongoManager.removeValue(this, idSpesificId);
                        }
                    }
                }
            }catch (Exception exception) {
                exception.printStackTrace();
            }
        });

    }

    public void handleGet(String source, NexusJsonDataContainer json) {
        NexusApplication.getApplication().getRedisManager().processTask(() -> {
            try {
                Optional<DataModel> dataModel = getData(json);
                DataModel targetModel;

                if (dataModel.isEmpty()) {
                    String idFieldName = getIdFieldName();
                    NexusJsonDataContainer extract = json.containsKey("data") ?
                            new NexusJsonDataContainer(mapper.writeValueAsString(json.get("data", Object.class))) : json;

                    Object idValue = extract.get(idFieldName, Object.class);
                    if (idValue == null) return;

                    targetModel = createModel(generateRawJson(idValue.toString()));
                    NexusApplication.getApplication().getDataContainer().addModelDirect(targetModel.getKey(), targetModel);
                    NexusApplication.getApplication().getRedisManager().setData(targetModel.getKey(), targetModel.getValueJson());
                } else {
                    targetModel = dataModel.get();
                }

                ObjectNode rootNode = JsonUtils.getMapper().createObjectNode();
                rootNode.put("protocol", addonId());
                rootNode.put("source", "nexus");
                rootNode.put("type", "BROADCAST");
                rootNode.put("target", source);

                JsonNode dataNode = mapper.readTree(targetModel.getValueJson());
                rootNode.set("data", dataNode);

                NexusApplication.getApplication().getRedisManager().publish(RedisManager.CHANNEL+"_"+source, mapper.writeValueAsString(rootNode));

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public void handleSet(String source, NexusJsonDataContainer json) {
        NexusApplication.getApplication().getRedisManager().processTask(() -> {
            Optional<DataModel> dataModel = getData(json);
            try {
                String rawInput = json.containsKey("data") ?
                        JsonUtils.toJson(json.get("data", Object.class)) : json.toFullJson();

                if (dataModel.isEmpty()) {
                    DataModel newModel = createModel(modelInit(rawInput));
                    NexusApplication.getApplication().getDataContainer().addModelDirect(newModel.getKey(), newModel);
                    NexusApplication.getApplication().getRedisManager().setData(newModel.getKey(), newModel.getValueJson());
                } else {
                    dataModel.get().setValueJson(modelInitComp(rawInput));
                    NexusApplication.getApplication().getRedisManager().setData(dataModel.get().getKey(), dataModel.get().getValueJson());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public String modelInit(String json) {
        ObjectNode outputNode = mapper.createObjectNode();
        try {
            JsonNode inputNode = mapper.readTree(json);
            for (Field field : getClass().getDeclaredFields()) {
                if (field.isAnnotationPresent(DbDataModels.class)) {
                    field.setAccessible(true);
                    String fieldName = field.getName();
                    DbDataModels anno = field.getAnnotation(DbDataModels.class);
                    Object targetValue = convertToType(anno.defaultValue(), field.getType());
                    if (inputNode.has(fieldName) && !inputNode.get(fieldName).isNull()) {
                        targetValue = mapper.readerForUpdating(targetValue).readValue(inputNode.get(fieldName));
                    }
                    field.set(this, targetValue);
                    outputNode.set(fieldName, targetValue != null ? mapper.valueToTree(targetValue) : mapper.createObjectNode());
                }
            }
            return mapper.writeValueAsString(outputNode);
        } catch (Exception e) {
            e.printStackTrace();
            return "{}";
        }
    }

    public String modelInitComp(String json) {
        try {
            JsonNode rootNode = mapper.readTree(json);
            ObjectNode updatedNode = mapper.createObjectNode();

            for (Field field : getClass().getDeclaredFields()) {
                if (field.isAnnotationPresent(DbDataModels.class)) {
                    String fieldName = field.getName();
                    DbDataModels anno = field.getAnnotation(DbDataModels.class);
                    Object baseObj = convertToType(anno.defaultValue(), field.getType());
                    if (rootNode.has(fieldName) && !rootNode.get(fieldName).isNull()) {
                        baseObj = mapper.readerForUpdating(baseObj).readValue(rootNode.get(fieldName));
                    }
                    updatedNode.set(fieldName, mapper.valueToTree(baseObj));
                }
            }
            return mapper.writeValueAsString(updatedNode);
        } catch (Exception e) {
            return json;
        }
    }

    private Object convertToType(String value, Class<?> type) {
        if (value == null || value.isEmpty() || value.equals("{}")) {
            if (type == String.class) return "";
            if (type == int.class || type == Integer.class) return 0;
            if (type == boolean.class || type == Boolean.class) return false;
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                try { return mapper.readValue("{}", type); } catch (Exception ex) { return null; }
            }
        }

        if (type == String.class) return value;
        if (type == int.class || type == Integer.class) return Integer.parseInt(value);
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(value);

        try { return mapper.readValue(value, type); } catch (Exception e) { return null; }
    }
    public String getIdFieldName() {
        for (Field f : getClass().getDeclaredFields()) {
            if (f.isAnnotationPresent(DbDataModels.class) && f.getAnnotation(DbDataModels.class).isId()) return f.getName();
        }
        return "";
    }


    public Class getIdClassName() {
        for (Field f : getClass().getDeclaredFields()) {
            if (f.isAnnotationPresent(DbDataModels.class) && f.getAnnotation(DbDataModels.class).isId()) return f.getType();
        }
        return null;
    }

    public abstract String cacheKeyHeaderTag();
    public abstract String getDatabase();
    public abstract String getCollection();

    public String generateRawJson(String idValue) {
        String idName = getIdFieldName();
        return idName.isEmpty() ? "{}" : modelInit(NexusJsonBuilder.create().add(idName, idValue).build());
    }

    public DataModel createModel(String json) throws JsonProcessingException {
        NexusJsonDataContainer container = new NexusJsonDataContainer(json);
        String specificKey = getSpecificDbKeyFromJsonKeyToValue(container);
        String keyTag = cacheKeyHeaderTag() + "_" + specificKey;
        return new DataModel(keyTag, UUID.randomUUID().toString(), json, this, specificKey);
    }

    public Optional<DataModel> getData(NexusJsonDataContainer dataContainer) {
        try {


            NexusJsonDataContainer work = dataContainer.containsKey("data") ?
                    new NexusJsonDataContainer(JsonUtils.toJson(dataContainer.get("data", Object.class))) : dataContainer;

            String specificValue = getSpecificDbKeyFromJsonKeyToValue(work);
            if (specificValue.isEmpty() || specificValue.equals("null")) return Optional.empty();

            String keyTag = cacheKeyHeaderTag() + "_" + specificValue;
            NexusApplication app = NexusApplication.getApplication();

            Optional<DataModel> l1 = app.getDataContainer().getDataModelFromKey(keyTag);
            if (l1.isPresent()) {
                DataModel m = l1.get();
                m.setValueJson(modelInitComp(m.getValueJson()));
                return l1;
            }

            if (app.getRedisManager().exists(keyTag)) {
                DataModel m = new DataModel(keyTag, UUID.randomUUID().toString(), modelInitComp(app.getRedisManager().getData(keyTag).get()), this, specificValue);
                app.getDataContainer().addModelFix(keyTag, m);
                return Optional.of(m);
            }

            String dbJson = app.getMongoManager().getValue(this, specificValue).join();
            if (dbJson != null) {
                DataModel m = new DataModel(keyTag, UUID.randomUUID().toString(), modelInitComp(dbJson), this, specificValue);
                app.getDataContainer().addModel(keyTag, m);
                return Optional.of(m);
            }
        } catch (Exception e) { e.printStackTrace(); }
        return Optional.empty();
    }

    public String getSpecificDbKeyFromJsonKeyToValue(NexusJsonDataContainer dataContainer) {
        String idName = getIdFieldName();
        if (idName.isEmpty()) return "";
        Object val = dataContainer.get(idName, Object.class);
        return val != null ? val.toString() : "";
    }

    public enum RequestType { SET_DATA, GET_DATA, UPDATE_DATA, REMOVE_DATA, BROADCAST, LOAD_CACHE }
}