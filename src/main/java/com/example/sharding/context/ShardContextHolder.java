package com.example.sharding.context;

/**
 * ThreadLocal holder that stores the current shard index and the
 * read/write role for the duration of a single request/transaction.
 *
 * <p>Always call {@link #clear()} in a finally block to avoid
 * context leaking into the next request on the same thread.</p>
 */
public class ShardContextHolder {

    public enum Role {
        PRIMARY,   // handles writes
        REPLICA    // handles reads
    }

    private static final ThreadLocal<Integer> CURRENT_SHARD = new ThreadLocal<>();
    private static final ThreadLocal<Role>    CURRENT_ROLE  = new ThreadLocal<>();

    private ShardContextHolder() {}

    public static void setShard(int shardIndex) {
        CURRENT_SHARD.set(shardIndex);
    }

    public static Integer getShard() {
        return CURRENT_SHARD.get();
    }

    public static void setRole(Role role) {
        CURRENT_ROLE.set(role);
    }

    /** Returns the current role, defaulting to PRIMARY if not set. */
    public static Role getRole() {
        return CURRENT_ROLE.get() != null ? CURRENT_ROLE.get() : Role.PRIMARY;
    }

    /** Clears both shard index and role from the current thread. */
    public static void clear() {
        CURRENT_SHARD.remove();
        CURRENT_ROLE.remove();
    }
}
