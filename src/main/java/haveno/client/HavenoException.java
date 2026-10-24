package haveno.client;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * Thrown when a call to the Haveno daemon fails.
 *
 * <p>Wraps the underlying gRPC error and exposes its status code, so callers can tell
 * apart, for example, a wrong password ({@link Status.Code#UNAUTHENTICATED}), a daemon
 * that isn't running ({@link Status.Code#UNAVAILABLE}) or a call that took too long
 * ({@link Status.Code#DEADLINE_EXCEEDED}). For errors raised by Haveno itself (such as
 * "offer not found"), {@link #getMessage()} carries the daemon's own error text.
 */
public class HavenoException extends RuntimeException {

    private final Status.Code code;

    public HavenoException(String message, Status.Code code, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** The gRPC status code of the failed call. */
    public Status.Code getCode() {
        return code;
    }

    /** True if the daemon rejected the API password. */
    public boolean isUnauthenticated() {
        return code == Status.Code.UNAUTHENTICATED;
    }

    /** True if the daemon could not be reached (not running, wrong host/port). */
    public boolean isUnavailable() {
        return code == Status.Code.UNAVAILABLE;
    }

    static HavenoException from(StatusRuntimeException e) {
        Status status = e.getStatus();
        String description = status.getDescription();
        String message = description != null && !description.isBlank()
                ? description
                : status.getCode().name();
        return new HavenoException(message, status.getCode(), e);
    }
}
