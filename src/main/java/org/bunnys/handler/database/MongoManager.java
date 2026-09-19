package org.bunnys.handler.database;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bunnys.utils.BunnyLog;

import java.util.concurrent.TimeUnit;

import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

/**
 * Owns the MongoDB client and its settings.
 *
 * <h2>Timeouts</h2>
 * Bounded by {@link #OPERATION_TIMEOUT_SECONDS}, the driver's client-side operation
 * timeout. It covers the <em>whole</em> operation - selecting a server, checking a
 * connection out of the pool, every round trip, and draining the cursor - so a command
 * worker cannot be parked indefinitely by a database that has stopped answering.
 *
 * <p>This replaces a three-second socket read timeout. A socket timeout bounds each
 * individual network read rather than the operation, which made it both too tight and
 * too blunt: an index build or a large export legitimately keeps the socket quiet for
 * longer than that, and when the limit was hit the driver tore the connection down and
 * churned the pool rather than failing one call. An operation budget expresses the thing
 * that was actually wanted - "no single command may hang a worker" - and lets the
 * sub-timeouts be generous enough to survive a replica-set election.
 *
 * <h2>Pool size</h2>
 * The driver defaults to 100 connections, which nothing here can drive. The size is
 * passed in by the hub, derived from its command worker count plus headroom, so the two
 * cannot drift apart - fewer connections than workers would stall workers on connection
 * checkout, which looks exactly like a slow query but is harder to find. Idle connections
 * are reaped, which matters when the cluster is shared with other bots.
 */
public class MongoManager {

    /**
     * Whole-operation budget.
     *
     * <p>Roughly fifty times a healthy round trip, and comfortably above an index build
     * on collections this size - while still well short of "a worker is gone".
     */
    private static final long OPERATION_TIMEOUT_SECONDS = 10L;

    /** Supplied by the hub, sized from its command worker count. */
    private final int maxPoolSize;

    /** Kept warm so the common path never pays connection setup. */
    private static final int MIN_POOL_SIZE = 2;

    /** A quiet bot should not hold a cluster-wide connection slot open all night. */
    private static final long MAX_IDLE_MINUTES = 10L;

    /**
     * Long enough to ride out a primary election, which the operation budget above still
     * caps. The previous three seconds meant an ordinary failover surfaced as a failure.
     */
    private static final long SERVER_SELECTION_SECONDS = 15L;

    private final MongoClient client;
    private final MongoDatabase database;

    public MongoManager(String uri, String databaseName, int maxPoolSize) {
        if (uri == null) {
            throw new IllegalArgumentException("[Database] No MongoDB URI provided.");
        }

        this.maxPoolSize = Math.max(MIN_POOL_SIZE, maxPoolSize);

        try {
            CodecRegistry pojoCodecRegistry = fromRegistries(
                    MongoClientSettings.getDefaultCodecRegistry(),
                    fromProviders(PojoCodecProvider.builder().automatic(true).build()));

            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(uri))
                    .codecRegistry(pojoCodecRegistry)

                    // Names this bot in the cluster's logs and profiler. The cluster is
                    // shared, so without it every slow query is anonymous.
                    .applicationName("BeastarsBot")

                    // The one timeout that matters: no operation outlives it.
                    .timeout(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

                    .applyToConnectionPoolSettings(builder -> builder
                            .maxSize(this.maxPoolSize)
                            .minSize(MIN_POOL_SIZE)
                            .maxConnectionIdleTime(MAX_IDLE_MINUTES, TimeUnit.MINUTES))

                    .applyToSocketSettings(builder -> builder
                            .connectTimeout(5, TimeUnit.SECONDS))

                    .applyToClusterSettings(builder -> builder
                            .serverSelectionTimeout(SERVER_SELECTION_SECONDS, TimeUnit.SECONDS))

                    // Both default to true; stated so a driver default change cannot
                    // quietly turn off recovery from a transient blip.
                    .retryWrites(true)
                    .retryReads(true)
                    .build();

            this.client = MongoClients.create(settings);
            this.database = this.client.getDatabase(databaseName);

            this.database.runCommand(new Document("ping", 1));
            BunnyLog.success("[Database] Successfully connected to MongoDB cluster!");

        } catch (Exception e) {
            // Throw exception to prevent zombie state
            BunnyLog.error("[Database] Critical failure establishing connection", e);
            throw new IllegalStateException("Failed to connect to MongoDB", e);
        }
    }

    public <T> MongoCollection<T> getCollection(Class<T> clazz, String collectionName) {
        return database.getCollection(collectionName, clazz);
    }

    public MongoDatabase getDatabase() {
        return this.database;
    }

    /**
     * Whether the cluster is reachable <em>now</em>.
     *
     * <p>Was a boolean set once in the constructor and never touched again, so it kept
     * answering "yes" long after a cluster had gone away - the one question it exists to
     * answer was the one it could not. This reads the driver's own topology view, which
     * its background monitor keeps current, and costs no round trip.
     */
    public boolean isConnected() {
        try {
            return client.getClusterDescription().hasReadableServer(ReadPreference.primaryPreferred());
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void disconnect() {
        if (this.client != null) {
            this.client.close();
            BunnyLog.info("[Database] Connection pool closed cleanly.");
        }
    }
}
