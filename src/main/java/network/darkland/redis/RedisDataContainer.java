package network.darkland.redis;

import network.darkland.NexusApplication;
import network.darkland.model.DataModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Veri Senkronizasyon Merkezi
 * 1000+ veride işlemciyi yormaması için tüm ağır döngüler processTask üzerinden işlenir.
 */
public class RedisDataContainer {

    private final ConcurrentHashMap<String, DataModel> idToDataList;
    private final Set<String> dirtyKeys;

    public RedisDataContainer() {
        this.idToDataList = new ConcurrentHashMap<>();
        this.dirtyKeys = ConcurrentHashMap.newKeySet();

        RedisManager rm = NexusApplication.getApplication().getRedisManager();
        rm.scheduleTask(this::startAutoSyncTask, 30, 30, TimeUnit.SECONDS);
        rm.scheduleTask(this::startL1SyncTask, 45, 45, TimeUnit.SECONDS);
        rm.scheduleTask(this::startReconciliationTask, 5, 5, TimeUnit.MINUTES);
    }

    private void startL1SyncTask() {

        System.out.println("l1 sync");
        if (idToDataList.isEmpty()) return;

        RedisManager redisManager = NexusApplication.getApplication().getRedisManager();

        redisManager.processTask(() -> {
            idToDataList.forEach((key, model) -> {
                if (dirtyKeys.contains(key)) return;

                redisManager.getData(key).ifPresent(redisJson -> {
                    if (!redisJson.equals(model.getValueJson())) {
                        model.setValueJson(redisJson);
                    }
                });
            });
        });
    }

    private void startAutoSyncTask() {

        System.out.println("Auto save");
        if (dirtyKeys.isEmpty()) return;
        List<String> keysToSync = new ArrayList<>(dirtyKeys);
        dirtyKeys.removeAll(keysToSync);

        RedisManager redisManager = NexusApplication.getApplication().getRedisManager();

        redisManager.processTask(() -> {
            for (String key : keysToSync) {
                DataModel model = idToDataList.get(key);
                if (model != null) {
                    NexusApplication.getApplication().getMongoManager()
                            .setValue(model.getAddon(), model.getSpecificDbKey(), model.getValueJson());
                }
            }
        });
    }

    private void startReconciliationTask() {


        if (idToDataList.isEmpty()) return;


        System.out.println("reconcl sync");

        RedisManager redisManager = NexusApplication.getApplication().getRedisManager();

        redisManager.processTask(() -> {
            idToDataList.forEach((keyTag, model) -> {
                if (dirtyKeys.contains(keyTag)) return;

                NexusApplication.getApplication().getMongoManager().getValue(model.getAddon(), model.getSpecificDbKey())
                        .thenAccept(dbJson -> {
                            if (dbJson == null) return;
                            try {
                                String cleanDbJson = model.getAddon().modelInitComp(dbJson);
                                if (!cleanDbJson.equals(model.getValueJson())) {
                                    model.setValueJson(cleanDbJson);
                                    redisManager.setData(keyTag, cleanDbJson);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
            });
        });
    }

    public void addModel(String key, DataModel model) {
        updateInternal(key, model);
        dirtyKeys.add(key);
    }

    public void addModelFix(String key, DataModel model) {
        idToDataList.put(key, model);
        dirtyKeys.add(key);
    }

    public void removeModel(String key) {
        if (idToDataList.containsKey(key)) {
            dirtyKeys.remove(key);
            idToDataList.remove(key);
            NexusApplication.getApplication().getRedisManager().deleteData(key);
        }
    }

    public void addModelDirect(String key, DataModel model) {
        updateInternal(key, model);
        NexusApplication.getApplication().getRedisManager().processTask(() -> {
            NexusApplication.getApplication().getMongoManager()
                    .setValue(model.getAddon(), model.getSpecificDbKey(), model.getValueJson());
        });
    }

    private void updateInternal(String key, DataModel model) {
        idToDataList.put(key, model);
        NexusApplication.getApplication().getRedisManager().setData(key, model.getValueJson());
    }

    public Optional<DataModel> getDataModelFromId(String id) {
        return idToDataList.values().stream().filter(dm -> dm.getId().equals(id)).findAny();
    }

    public Optional<DataModel> getDataModelFromKey(String key) {
        return Optional.ofNullable(idToDataList.get(key));
    }

    public Set<String> getDirtyKeys() { return dirtyKeys; }
    public int getDataSize() { return this.idToDataList.size(); }
}