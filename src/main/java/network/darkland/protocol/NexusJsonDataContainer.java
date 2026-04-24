package network.darkland.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import network.darkland.util.JsonUtils;

import java.util.HashMap;
import java.util.Map;

public class NexusJsonDataContainer {

    private final Map<String, Object> dataMap = new HashMap<>();
    private final ObjectMapper mapper = JsonUtils.getMapper();

    public NexusJsonDataContainer() {

    }
    public NexusJsonDataContainer(String json) throws JsonProcessingException {
        Map<String, Object> map = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        this.dataMap.putAll(map);
    }

    public <T> T get(String key, Class<T> clazz) {
        Object value = dataMap.get(key);
        if (value == null) return null;
        if (clazz.isInstance(value)) return clazz.cast(value);
        return mapper.convertValue(value, clazz);
    }

    public <T> void set(String key, T value) {
        dataMap.put(key, value);
    }

    public String toFullJson() {
        try {
            return mapper.writeValueAsString(dataMap);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            return "{}";
        }
    }

    public boolean containsKey(String key) {
        return dataMap.containsKey(key);
    }

    @Override
    public String toString() {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dataMap);
        } catch (Exception e) {
            return "NexusJsonDataContainer{data=" + dataMap + "}";
        }
    }
}