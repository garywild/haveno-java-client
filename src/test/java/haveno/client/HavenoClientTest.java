package haveno.client;

import com.google.protobuf.ByteString;
import haveno.proto.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the client against an in-process fake daemon that checks the API password the same
 * way Haveno's PasswordAuthInterceptor does.
 */
class HavenoClientTest {

    private static final String PASSWORD = "apitest";

    private Server server;
    private ManagedChannel channel;
    private HavenoClient client;

    private final AtomicReference<GetOffersRequest> lastGetOffers = new AtomicReference<>();
    private final AtomicReference<PostOfferRequest> lastPostOffer = new AtomicReference<>();
    private final List<RestoreAccountRequest> restoreChunks = new CopyOnWriteArrayList<>();
    private final CountDownLatch notificationStreamCancelled = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(ServerInterceptors.intercept(new FakeVersion(), new RequirePassword()))
                .addService(ServerInterceptors.intercept(new FakeOffers(), new RequirePassword()))
                .addService(ServerInterceptors.intercept(new FakeTrades(), new RequirePassword()))
                .addService(ServerInterceptors.intercept(new FakeAccount(), new RequirePassword()))
                .addService(ServerInterceptors.intercept(new FakeNotifications(), new RequirePassword()))
                .addService(ServerInterceptors.intercept(new FakePrice(), new RequirePassword()))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client = HavenoClient.builder().channel(channel).password(PASSWORD).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void sendsPasswordAndReturnsVersion() {
        assertEquals("1.2.3", client.getVersion());
    }

    @Test
    void wrongPasswordIsUnauthenticated() {
        try (HavenoClient wrong = HavenoClient.builder().channel(channel).password("nope").build()) {
            HavenoException e = assertThrows(HavenoException.class, wrong::getVersion);
            assertTrue(e.isUnauthenticated());
            assertEquals("incorrect 'password' rpc header value", e.getMessage());
        }
    }

    @Test
    void passwordIsRequired() {
        assertThrows(NullPointerException.class, () -> HavenoClient.builder().channel(channel).build());
    }

    @Test
    void getOffersPassesCurrencyAndDirection() {
        List<OfferInfo> offers = client.getOffers("EUR", OfferDirection.SELL);
        assertEquals(1, offers.size());
        assertEquals("offer-1", offers.get(0).getId());
        assertEquals("EUR", lastGetOffers.get().getCurrencyCode());
        assertEquals("SELL", lastGetOffers.get().getDirection());

        client.getOffers("USD");
        assertEquals("", lastGetOffers.get().getDirection());
    }

    @Test
    void daemonErrorMessageIsKept() {
        HavenoException e = assertThrows(HavenoException.class, () -> client.getOffer("missing"));
        assertEquals(Status.Code.NOT_FOUND, e.getCode());
        assertEquals("offer with id 'missing' not found", e.getMessage());
    }

    @Test
    void postOfferWithMarketPrice() {
        PostOfferParams params = PostOfferParams.builder()
                .direction(OfferDirection.BUY)
                .currencyCode("EUR")
                .amount(XmrUnits.toAtomicUnits("1.5"))
                .marketPriceMarginPct(2.5)
                .paymentAccountId("acct-1")
                .build();
        OfferInfo offer = client.postOffer(params);

        PostOfferRequest sent = lastPostOffer.get();
        assertEquals("BUY", sent.getDirection());
        assertTrue(sent.getUseMarketBasedPrice());
        assertEquals(2.5, sent.getMarketPriceMarginPct());
        assertEquals("", sent.getPrice());
        assertEquals(1_500_000_000_000L, sent.getAmount());
        assertEquals(sent.getAmount(), sent.getMinAmount(), "min amount defaults to full amount");
        assertEquals(0.15, sent.getSecurityDepositPct());
        assertEquals("new-offer", offer.getId());
    }

    @Test
    void postOfferRequiresExactlyOnePriceMode() {
        PostOfferParams.Builder neither = PostOfferParams.builder()
                .direction(OfferDirection.SELL).currencyCode("EUR").amount(1).paymentAccountId("a");
        assertThrows(IllegalArgumentException.class, neither::build);

        PostOfferParams.Builder both = PostOfferParams.builder()
                .direction(OfferDirection.SELL).currencyCode("EUR").amount(1).paymentAccountId("a")
                .fixedPrice("150").marketPriceMarginPct(1);
        assertThrows(IllegalArgumentException.class, both::build);
    }

    @Test
    void takeOfferFailureReasonBecomesException() {
        HavenoException e = assertThrows(HavenoException.class,
                () -> client.takeOffer("unavailable-offer", "acct-1"));
        assertEquals("offer no longer available", e.getMessage());
        assertEquals(Status.Code.FAILED_PRECONDITION, e.getCode());

        TradeInfo trade = client.takeOffer("offer-1", "acct-1");
        assertEquals("trade-1", trade.getTradeId());
    }

    @Test
    void getTradesDefaultsToOpen() {
        assertEquals("OPEN", client.getTrades().get(0).getState());
        assertEquals("CLOSED", client.getTrades(GetTradesRequest.Category.CLOSED).get(0).getState());
    }

    @Test
    void marketPrices() {
        assertEquals(161.25, client.getPrice("EUR"));
        assertEquals(2, client.getPrices().size());
    }

    @Test
    void backupAccountJoinsStreamedChunks() {
        assertArrayEquals("PK-zip-bytes".getBytes(), client.backupAccount());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        client.backupAccount(out);
        assertEquals("PK-zip-bytes", out.toString());
    }

    @Test
    void restoreAccountUploadsInChunks() {
        byte[] zip = new byte[HavenoClient.RESTORE_CHUNK_SIZE * 2 + 10];
        for (int i = 0; i < zip.length; i++) {
            zip[i] = (byte) i;
        }
        client.restoreAccount(zip);

        assertEquals(3, restoreChunks.size());
        assertEquals(0, restoreChunks.get(0).getOffset());
        assertEquals(HavenoClient.RESTORE_CHUNK_SIZE, restoreChunks.get(1).getOffset());
        assertTrue(restoreChunks.get(0).getHasMore());
        assertTrue(restoreChunks.get(1).getHasMore());
        assertFalse(restoreChunks.get(2).getHasMore());
        assertEquals(10, restoreChunks.get(2).getZipBytes().size());

        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (RestoreAccountRequest chunk : restoreChunks) {
            assertEquals(zip.length, chunk.getTotalLength());
            joined.writeBytes(chunk.getZipBytes().toByteArray());
        }
        assertArrayEquals(zip, joined.toByteArray());
    }

    @Test
    void notificationsSkipKeepAliveAndCanBeClosed() throws Exception {
        List<NotificationMessage> received = new CopyOnWriteArrayList<>();
        Subscription sub = client.subscribeNotifications(received::add, null);

        waitFor(() -> received.size() >= 2);
        assertEquals(2, received.size(), "keep-alive is filtered out");
        assertEquals(NotificationMessage.NotificationType.APP_INITIALIZED, received.get(0).getType());
        assertEquals(NotificationMessage.NotificationType.TRADE_UPDATE, received.get(1).getType());
        assertTrue(sub.isActive());

        sub.close();
        assertFalse(sub.isActive());
        assertTrue(notificationStreamCancelled.await(5, TimeUnit.SECONDS), "server saw the cancel");
        sub.close(); // idempotent
    }

    @Test
    void notificationErrorsReachErrorCallback() throws Exception {
        try (HavenoClient wrong = HavenoClient.builder().channel(channel).password("nope").build()) {
            AtomicReference<HavenoException> error = new AtomicReference<>();
            Subscription sub = wrong.subscribeNotifications(n -> fail("no notifications expected"), error::set);
            waitFor(() -> error.get() != null);
            assertTrue(error.get().isUnauthenticated());
            assertFalse(sub.isActive());
        }
    }

    @Test
    void closingClientDoesNotCloseCallersChannel() {
        client.close();
        assertFalse(channel.isShutdown());
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting");
            }
            Thread.sleep(10);
        }
    }

    // ------------------------------------------------------------------------------------
    // Fake daemon
    // ------------------------------------------------------------------------------------

    /** Same check as Haveno's PasswordAuthInterceptor. */
    private static final class RequirePassword implements ServerInterceptor {
        private static final Metadata.Key<String> KEY =
                Metadata.Key.of("password", Metadata.ASCII_STRING_MARSHALLER);

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            if (!PASSWORD.equals(headers.get(KEY))) {
                call.close(Status.UNAUTHENTICATED.withDescription("incorrect 'password' rpc header value"),
                        new Metadata());
                return new ServerCall.Listener<>() {
                };
            }
            return next.startCall(call, headers);
        }
    }

    private static final class FakeVersion extends GetVersionGrpc.GetVersionImplBase {
        @Override
        public void getVersion(GetVersionRequest request, StreamObserver<GetVersionReply> out) {
            out.onNext(GetVersionReply.newBuilder().setVersion("1.2.3").build());
            out.onCompleted();
        }
    }

    private final class FakeOffers extends OffersGrpc.OffersImplBase {
        @Override
        public void getOffers(GetOffersRequest request, StreamObserver<GetOffersReply> out) {
            lastGetOffers.set(request);
            out.onNext(GetOffersReply.newBuilder()
                    .addOffers(OfferInfo.newBuilder().setId("offer-1").setPrice("161.25"))
                    .build());
            out.onCompleted();
        }

        @Override
        public void getOffer(GetOfferRequest request, StreamObserver<GetOfferReply> out) {
            out.onError(Status.NOT_FOUND
                    .withDescription("offer with id '" + request.getId() + "' not found")
                    .asRuntimeException());
        }

        @Override
        public void postOffer(PostOfferRequest request, StreamObserver<PostOfferReply> out) {
            lastPostOffer.set(request);
            out.onNext(PostOfferReply.newBuilder().setOffer(OfferInfo.newBuilder().setId("new-offer")).build());
            out.onCompleted();
        }
    }

    private static final class FakeTrades extends TradesGrpc.TradesImplBase {
        @Override
        public void takeOffer(TakeOfferRequest request, StreamObserver<TakeOfferReply> out) {
            TakeOfferReply.Builder reply = TakeOfferReply.newBuilder();
            if (request.getOfferId().equals("unavailable-offer")) {
                reply.setFailureReason(AvailabilityResultWithDescription.newBuilder()
                        .setDescription("offer no longer available"));
            } else {
                reply.setTrade(TradeInfo.newBuilder().setTradeId("trade-1"));
            }
            out.onNext(reply.build());
            out.onCompleted();
        }

        @Override
        public void getTrades(GetTradesRequest request, StreamObserver<GetTradesReply> out) {
            out.onNext(GetTradesReply.newBuilder()
                    .addTrades(TradeInfo.newBuilder().setState(request.getCategory().name()))
                    .build());
            out.onCompleted();
        }
    }

    private final class FakeAccount extends AccountGrpc.AccountImplBase {
        @Override
        public void backupAccount(BackupAccountRequest request, StreamObserver<BackupAccountReply> out) {
            for (String part : new String[]{"PK-", "zip-", "bytes"}) {
                out.onNext(BackupAccountReply.newBuilder().setZipBytes(ByteString.copyFromUtf8(part)).build());
            }
            out.onCompleted();
        }

        @Override
        public void restoreAccount(RestoreAccountRequest request, StreamObserver<RestoreAccountReply> out) {
            restoreChunks.add(request);
            out.onNext(RestoreAccountReply.getDefaultInstance());
            out.onCompleted();
        }
    }

    private final class FakeNotifications extends NotificationsGrpc.NotificationsImplBase {
        @Override
        public void registerNotificationListener(RegisterNotificationListenerRequest request,
                                                 StreamObserver<NotificationMessage> out) {
            ((ServerCallStreamObserver<NotificationMessage>) out)
                    .setOnCancelHandler(notificationStreamCancelled::countDown);
            out.onNext(notification(NotificationMessage.NotificationType.APP_INITIALIZED));
            out.onNext(notification(NotificationMessage.NotificationType.KEEP_ALIVE));
            out.onNext(notification(NotificationMessage.NotificationType.TRADE_UPDATE));
            // stream stays open, like the real daemon
        }

        private NotificationMessage notification(NotificationMessage.NotificationType type) {
            return NotificationMessage.newBuilder().setType(type).setTitle(type.name()).build();
        }
    }

    private static final class FakePrice extends PriceGrpc.PriceImplBase {
        @Override
        public void getMarketPrice(MarketPriceRequest request, StreamObserver<MarketPriceReply> out) {
            out.onNext(MarketPriceReply.newBuilder().setPrice(161.25).build());
            out.onCompleted();
        }

        @Override
        public void getMarketPrices(MarketPricesRequest request, StreamObserver<MarketPricesReply> out) {
            out.onNext(MarketPricesReply.newBuilder()
                    .addMarketPrice(MarketPriceInfo.newBuilder().setCurrencyCode("EUR").setPrice(161.25))
                    .addMarketPrice(MarketPriceInfo.newBuilder().setCurrencyCode("USD").setPrice(175.10))
                    .build());
            out.onCompleted();
        }
    }
}
