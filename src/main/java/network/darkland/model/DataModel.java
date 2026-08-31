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

    /**
     * ÖNEMLİ: Bu metot artık Redis'e YAZMIYOR — sadece local state'i günceller
     * ve key'i "dirty" olarak işaretler (Mongo'ya flush edilmesi gerektiğini belirtir).
     *
     * Önceki sürümde bu setter gizlice Redis'e de yazıyordu, bu da çağıran
     * kodların (handleSet, handleIncrementData vb.) aynı veriyi tekrar Redis'e
     * yazmasına ve gereksiz round-trip'lere yol açıyordu.
     *
     * Redis'e asıl yazımdan SORUMLU olan taraf artık çağıran koddur:
     *   dataModel.setValueJson(json);              // sadece local state
     *   redisManager.setData(dataModel.getKey(), json, dataModel.getAddon()); // tek gerçek yazım
     */
    public void setValueJson(String valueJson) {
        this.valueJson = valueJson;
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