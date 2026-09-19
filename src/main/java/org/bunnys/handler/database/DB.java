package org.bunnys.handler.database;

import com.mongodb.client.MongoCollection;
import org.bson.conversions.Bson;

/**
 * Thin accessor over the live {@link MongoManager}.
 *
 * <h2>What is deliberately not here</h2>
 * The {@code save(...)} helpers are gone. They wrapped {@code replaceOne}, which
 * rewrites a whole document and silently discards any concurrent edit - the exact
 * pattern this project's invariants forbid. Nothing called them any more, and removing
 * them makes the rule unbreakable rather than merely documented: writes must use
 * targeted operators ({@code $set}, {@code $inc}, {@code $push}, {@code $pull}) through
 * {@link #getCollection}, ideally via {@code findOneAndUpdate} so the post-image can
 * refresh the cache in the same round trip.
 *
 * <p>{@code findMany}, {@code findById}, {@code deleteById} and {@code deleteMany} went
 * the same way as unused surface - the last two had no callers at all, and a helper
 * nobody calls is a helper nobody is maintaining. All are a few lines to restore from
 * history if a real caller ever appears.
 */
public class DB {

    /**
     * Volatile because it is written once on the startup thread and read from every
     * command worker and JDA callback thread afterwards.
     *
     * <p>In practice the {@code Thread.start()} calls that follow already establish the
     * necessary happens-before, so this is belt and braces - but a static handle that
     * every thread in the process reads should not depend on that reasoning holding
     * after the next change to startup ordering.
     */
    private static volatile MongoManager manager;

    /**
     * Binds the live manager. Idempotent by refusal rather than by silence.
     *
     * <p>A second call used to replace the manager and drop the first client on the
     * floor - never closed, its connection pool still open against a shared cluster.
     * Refusing makes a double-initialisation a startup crash instead of a slow leak.
     */
    public static void init(MongoManager mongoManager) {
        // Null first: without it, init(null) sails past the double-init check below and
        // leaves the handle exactly as unusable as it was, but now silently.
        if (mongoManager == null)
            throw new IllegalArgumentException("[Database] init() requires a manager.");

        if (manager != null)
            throw new IllegalStateException(
                    "[Database] init() called twice; the first client would leak its connection pool.");

        manager = mongoManager;
    }

    private static void checkConnection() {
        if (manager == null || manager.getDatabase() == null)
            throw new IllegalStateException(
                    "[Database] Critical: attempted an operation before MongoDB was initialized.");
    }

    /** The primary entry point: hand back the typed collection and use update operators on it. */
    public static <T> MongoCollection<T> getCollection(Class<T> clazz, String collectionName) {
        checkConnection();
        return manager.getCollection(clazz, collectionName);
    }

    /**
     * The raw database handle.
     *
     * <p>Needed for collection-level administration - creating a capped collection,
     * inspecting collection options - which has no per-collection equivalent. Ordinary
     * feature code should use {@link #getCollection} instead.
     */
    public static com.mongodb.client.MongoDatabase getDatabase() {
        checkConnection();
        return manager.getDatabase();
    }

    /** Find a single document matching a custom filter. */
    public static <T> T findOne(Class<T> clazz, String collectionName, Bson filter) {
        return getCollection(clazz, collectionName).find(filter).first();
    }
}
