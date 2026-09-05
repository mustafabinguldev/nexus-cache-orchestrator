package network.darkland.protocol;

@FunctionalInterface
public interface RequestHandler {
    void handle(DataAddon addon, String source, NexusJsonDataContainer json);
}