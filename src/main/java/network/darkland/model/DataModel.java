package network.darkland.model;

import network.darkland.NexusApplication;
import network.darkland.protocol.DataAddon;

public class DataModel {

    private String key;
    private String id;
    private String valueJson;

    private DataAddon addon;

    private String specificDbKey;



    public DataModel(String key, String id, String value, DataAddon addon, String specificDbKey) {
        this.key = key;
        this.id = id;
        this.valueJson = value;
        this.addon = addon;
        this.specificDbKey = specificDbKey;

    }

    public DataModel() {
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValueJson() {
        return valueJson;
    }

    public void setValueJson(String valueJson) {
        this.valueJson = valueJson;

        NexusApplication.getApplication().getRedisManager().setData(getKey(), valueJson);
        NexusApplication.getApplication().getDataContainer().getDirtyKeys().add(getKey());

    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }


    public DataAddon getAddon() {
        return addon;
    }

    public String getSpecificDbKey() {
        return specificDbKey;
    }
}
