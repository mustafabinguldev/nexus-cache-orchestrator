package network.darkland.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class NexusJsonBuilder {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final ObjectNode node;
    private NexusJsonBuilder() {
        this.node = mapper.createObjectNode();
    }
    public static NexusJsonBuilder create() {
        return new NexusJsonBuilder();
    }

    public NexusJsonBuilder add(String key, Object value) {
        node.set(key, mapper.valueToTree(value));
        return this;
    }

    public String build() {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            e.printStackTrace();
            return "{}";
        }
    }
}