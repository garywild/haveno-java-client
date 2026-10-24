package haveno.client;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/**
 * Adds the daemon's API password to every call, as the {@code password} metadata header
 * that Haveno's {@code PasswordAuthInterceptor} checks.
 */
final class PasswordInterceptor implements ClientInterceptor {

    static final Metadata.Key<String> PASSWORD_KEY =
            Metadata.Key.of("password", Metadata.ASCII_STRING_MARSHALLER);

    private final String password;

    PasswordInterceptor(String password) {
        this.password = password;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.put(PASSWORD_KEY, password);
                super.start(responseListener, headers);
            }
        };
    }
}
