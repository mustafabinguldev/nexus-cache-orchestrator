package network.darkland.db.mongo;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the lifecycle of the MongoDB driver connection: building the client from a connection
 * string, health-checking it, and closing it down. Deliberately knows nothing about addons,
 * collections, or documents — that's {@link MongoRepository}'s job. Splitting the two keeps
 * "how do we talk to Mongo" separate from "what do we store in Mongo", which is what lets
 * {@link network.darkland.db.sql.SqlConnectionManager} mirror this shape for SQL engines
 * without either one leaking the other's concerns.
 */
public final class MongoConnectionManager {

    private static final Logger LOGGER = Logger.getLogger(MongoConnectionManager.class.getName());

    private final MongoClient client;

    public MongoConnectionManager(String uri) {
        ConnectionString connectionString = new ConnectionString(uri);

        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToClusterSettings(builder ->
                        builder.serverSelectionTimeout(2, TimeUnit.SECONDS)
                )
                .applyToSocketSettings(builder ->
                        builder.connectTimeout(2, TimeUnit.SECONDS)
                                .readTimeout(2, TimeUnit.SECONDS)
                )
                .build();

        this.client = MongoClients.create(settings);
    }

    public MongoClient client() {
        return client;
    }

    public boolean verifyConnection() {
        try {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[MongoDB] Connection check failed", e);
            return false;
        }
    }

    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
