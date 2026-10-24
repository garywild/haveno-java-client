package haveno.client;

/** A live notification stream; close it to stop receiving notifications. */
public interface Subscription extends AutoCloseable {

    /** Stops the stream. Safe to call more than once. */
    @Override
    void close();

    /** True until the stream is closed by you, by the daemon, or by an error. */
    boolean isActive();
}
