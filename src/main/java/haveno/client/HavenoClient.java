package haveno.client;

import com.google.protobuf.ByteString;
import haveno.proto.grpc.*;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import protobuf.Alert;
import protobuf.Attachment;
import protobuf.Dispute;
import protobuf.DisputeResult;
import protobuf.PaymentAccount;
import protobuf.PaymentAccountForm;
import protobuf.PaymentAccountFormField;
import protobuf.PaymentMethod;
import protobuf.TradeStatistics3;
import protobuf.XmrNodeSettings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.io.ByteArrayInputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Java client for the Haveno daemon's gRPC API.
 *
 * <p>Connects to a running Haveno daemon (started with an API password) and exposes its
 * services as plain Java methods. Results are the API's own protobuf types
 * ({@link OfferInfo}, {@link TradeInfo}, ...), so every field the daemon returns is available.
 * XMR amounts are in atomic units; see {@link XmrUnits}.
 *
 * <pre>{@code
 * try (HavenoClient haveno = HavenoClient.builder()
 *         .host("localhost")
 *         .port(9998)
 *         .password("apitest")
 *         .build()) {
 *
 *     System.out.println("Daemon version " + haveno.getVersion());
 *     for (OfferInfo offer : haveno.getOffers("EUR", OfferDirection.SELL)) {
 *         System.out.println(offer.getId() + " " + offer.getPrice());
 *     }
 * }
 * }</pre>
 *
 * <p>Every method throws {@link HavenoException} when the daemon returns an error or can't be
 * reached. The client is thread-safe; create one per daemon and reuse it.
 */
public final class HavenoClient implements AutoCloseable {

    /** Default port of the Haveno daemon's API ({@code --apiPort}). */
    public static final int DEFAULT_PORT = 9998;

    /** Size of each chunk uploaded by {@link #restoreAccount(InputStream, long)}. */
    static final int RESTORE_CHUNK_SIZE = 1024 * 1024;

    /** Channel used for calls, with the password interceptor applied. */
    private final Channel channel;
    /** Channel to shut down on {@link #close()}, or null if the caller owns it. */
    private final ManagedChannel ownedChannel;
    private final Duration timeout;

    private HavenoClient(Channel channel, ManagedChannel ownedChannel, Duration timeout) {
        this.channel = channel;
        this.ownedChannel = ownedChannel;
        this.timeout = timeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The authenticated channel, for calling the generated stubs directly, e.g.
     * {@code OffersGrpc.newBlockingStub(client.getChannel())}.
     */
    public Channel getChannel() {
        return channel;
    }

    /** Closes the connection to the daemon. Does not stop the daemon; see {@link #stopDaemon()}. */
    @Override
    public void close() {
        if (ownedChannel == null) {
            return;
        }
        ownedChannel.shutdown();
        try {
            if (!ownedChannel.awaitTermination(5, TimeUnit.SECONDS)) {
                ownedChannel.shutdownNow();
            }
        } catch (InterruptedException e) {
            ownedChannel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------------
    // Version, help, daemon
    // ------------------------------------------------------------------------------------

    /** Version of the connected Haveno daemon. */
    public String getVersion() {
        return call(() -> stub(GetVersionGrpc.newBlockingStub(channel))
                .getVersion(GetVersionRequest.getDefaultInstance())
                .getVersion());
    }

    /** Help text for an API method, e.g. {@code "getoffers"}. */
    public String getMethodHelp(String methodName) {
        return call(() -> stub(HelpGrpc.newBlockingStub(channel))
                .getMethodHelp(GetMethodHelpRequest.newBuilder().setMethodName(methodName).build())
                .getMethodHelp());
    }

    /** Shuts down the Haveno daemon. */
    public void stopDaemon() {
        call(() -> stub(ShutdownServerGrpc.newBlockingStub(channel)).stop(StopRequest.getDefaultInstance()));
    }

    // ------------------------------------------------------------------------------------
    // Account
    // ------------------------------------------------------------------------------------

    public boolean accountExists() {
        return call(() -> account().accountExists(AccountExistsRequest.getDefaultInstance()).getAccountExists());
    }

    public boolean isAccountOpen() {
        return call(() -> account().isAccountOpen(IsAccountOpenRequest.getDefaultInstance()).getIsAccountOpen());
    }

    /** Creates a new Haveno account protected by {@code password}. */
    public void createAccount(String password) {
        call(() -> account().createAccount(CreateAccountRequest.newBuilder().setPassword(password).build()));
    }

    /**
     * Creates a new account from an existing XMR wallet seed.
     *
     * @param restoreDate date to scan the wallet from, as {@code YYYY-MM-DD}, or null to scan
     *                    from the wallet's creation
     */
    public void createAccountFromSeed(String password, String seed, String restoreDate) {
        CreateAccountFromSeedRequest.Builder request = CreateAccountFromSeedRequest.newBuilder()
                .setPassword(password)
                .setSeed(seed);
        if (restoreDate != null) {
            request.setRestoreDate(restoreDate);
        }
        call(() -> account().createAccountFromSeed(request.build()));
    }

    /** Opens the existing account with its password. */
    public void openAccount(String password) {
        call(() -> account().openAccount(OpenAccountRequest.newBuilder().setPassword(password).build()));
    }

    /** True once the daemon has finished starting up and the account is usable. */
    public boolean isAppInitialized() {
        return call(() -> account().isAppInitialized(IsAppInitializedRequest.getDefaultInstance())
                .getIsAppInitialized());
    }

    /** Changes the account password; returns the paths of retained wallet backups. */
    public List<String> changePassword(String oldPassword, String newPassword) {
        return call(() -> account().changePassword(ChangePasswordRequest.newBuilder()
                .setOldPassword(oldPassword)
                .setNewPassword(newPassword)
                .build()).getRetainedWalletBackupsList());
    }

    public void closeAccount() {
        call(() -> account().closeAccount(CloseAccountRequest.getDefaultInstance()));
    }

    /** Permanently deletes the account and its wallet. Back up first! */
    public void deleteAccount() {
        call(() -> account().deleteAccount(DeleteAccountRequest.getDefaultInstance()));
    }

    /** Streams a zip backup of the account to {@code out}. Does not close {@code out}. */
    public void backupAccount(OutputStream out) {
        call(() -> {
            Iterator<BackupAccountReply> chunks = streamingStub(AccountGrpc.newBlockingStub(channel))
                    .backupAccount(BackupAccountRequest.getDefaultInstance());
            try {
                while (chunks.hasNext()) {
                    chunks.next().getZipBytes().writeTo(out);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("failed writing account backup", e);
            }
            return null;
        });
    }

    /** Returns a zip backup of the account. */
    public byte[] backupAccount() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        backupAccount(out);
        return out.toByteArray();
    }

    /**
     * Restores an account from a zip backup made with {@link #backupAccount}. The backup is
     * uploaded in chunks.
     *
     * @param zip         the backup contents
     * @param totalLength total size of the backup in bytes
     */
    public void restoreAccount(InputStream zip, long totalLength) {
        byte[] buffer = new byte[RESTORE_CHUNK_SIZE];
        long offset = 0;
        try {
            while (offset < totalLength) {
                int read = zip.readNBytes(buffer, 0, (int) Math.min(buffer.length, totalLength - offset));
                if (read <= 0) {
                    throw new IllegalArgumentException(
                            "backup ended after " + offset + " of " + totalLength + " bytes");
                }
                RestoreAccountRequest request = RestoreAccountRequest.newBuilder()
                        .setZipBytes(ByteString.copyFrom(buffer, 0, read))
                        .setOffset(offset)
                        .setTotalLength(totalLength)
                        .setHasMore(offset + read < totalLength)
                        .build();
                call(() -> account().restoreAccount(request));
                offset += read;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading account backup", e);
        }
    }

    /** Restores an account from a zip backup made with {@link #backupAccount()}. */
    public void restoreAccount(byte[] zip) {
        restoreAccount(new ByteArrayInputStream(zip), zip.length);
    }

    // ------------------------------------------------------------------------------------
    // Monero node connections
    // ------------------------------------------------------------------------------------

    public void addMoneroConnection(UrlConnection connection) {
        call(() -> connections().addConnection(AddConnectionRequest.newBuilder().setConnection(connection).build()));
    }

    /** Adds a Monero node by URL with the given priority (lower is preferred). */
    public void addMoneroConnection(String url, int priority) {
        addMoneroConnection(UrlConnection.newBuilder().setUrl(url).setPriority(priority).build());
    }

    public void removeMoneroConnection(String url) {
        call(() -> connections().removeConnection(RemoveConnectionRequest.newBuilder().setUrl(url).build()));
    }

    public UrlConnection getMoneroConnection() {
        return call(() -> connections().getConnection(GetConnectionRequest.getDefaultInstance()).getConnection());
    }

    public List<UrlConnection> getMoneroConnections() {
        return call(() -> connections().getConnections(GetConnectionsRequest.getDefaultInstance())
                .getConnectionsList());
    }

    /** Switches to the Monero node with this URL. */
    public void setMoneroConnection(String url) {
        call(() -> connections().setConnection(SetConnectionRequest.newBuilder().setUrl(url).build()));
    }

    /** Switches to this Monero node, adding it if needed. */
    public void setMoneroConnection(UrlConnection connection) {
        call(() -> connections().setConnection(SetConnectionRequest.newBuilder().setConnection(connection).build()));
    }

    /** Checks the current Monero node and returns it with its online status. */
    public UrlConnection checkMoneroConnection() {
        return call(() -> connections().checkConnection(CheckConnectionRequest.getDefaultInstance()).getConnection());
    }

    public UrlConnection getBestMoneroConnection() {
        return call(() -> connections().getBestConnection(GetBestConnectionRequest.getDefaultInstance())
                .getConnection());
    }

    /** Whether to switch automatically to the best available Monero node. */
    public void setAutoSwitch(boolean autoSwitch) {
        call(() -> connections().setAutoSwitch(SetAutoSwitchRequest.newBuilder().setAutoSwitch(autoSwitch).build()));
    }

    public boolean getAutoSwitch() {
        return call(() -> connections().getAutoSwitch(GetAutoSwitchRequest.getDefaultInstance()).getAutoSwitch());
    }

    // ------------------------------------------------------------------------------------
    // Local Monero node
    // ------------------------------------------------------------------------------------

    public boolean isMoneroNodeOnline() {
        return call(() -> xmrNode().isXmrNodeOnline(IsXmrNodeOnlineRequest.getDefaultInstance()).getIsRunning());
    }

    public XmrNodeSettings getMoneroNodeSettings() {
        return call(() -> xmrNode().getXmrNodeSettings(GetXmrNodeSettingsRequest.getDefaultInstance())
                .getSettings());
    }

    public void startMoneroNode(XmrNodeSettings settings) {
        call(() -> xmrNode().startXmrNode(StartXmrNodeRequest.newBuilder().setSettings(settings).build()));
    }

    public void stopMoneroNode() {
        call(() -> xmrNode().stopXmrNode(StopXmrNodeRequest.getDefaultInstance()));
    }

    // ------------------------------------------------------------------------------------
    // Prices and market data
    // ------------------------------------------------------------------------------------

    /** Market price of 1 XMR in the given currency, e.g. {@code "EUR"} or {@code "BTC"}. */
    public double getPrice(String currencyCode) {
        return call(() -> price().getMarketPrice(MarketPriceRequest.newBuilder()
                .setCurrencyCode(currencyCode).build()).getPrice());
    }

    /** Market prices of XMR in all supported currencies. */
    public List<MarketPriceInfo> getPrices() {
        return call(() -> price().getMarketPrices(MarketPricesRequest.getDefaultInstance()).getMarketPriceList());
    }

    /** Cumulative buy and sell depth of the order book for a currency. */
    public MarketDepthInfo getMarketDepth(String currencyCode) {
        return call(() -> price().getMarketDepth(MarketDepthRequest.newBuilder()
                .setCurrencyCode(currencyCode).build()).getMarketDepth());
    }

    /** Statistics of completed trades on the network. */
    public List<TradeStatistics3> getTradeStatistics() {
        return call(() -> stub(GetTradeStatisticsGrpc.newBlockingStub(channel))
                .getTradeStatistics(GetTradeStatisticsRequest.getDefaultInstance())
                .getTradeStatisticsList());
    }

    // ------------------------------------------------------------------------------------
    // Wallet
    // ------------------------------------------------------------------------------------

    /** XMR balances: total, available, pending and reserved for offers and trades. */
    public XmrBalanceInfo getBalances() {
        return call(() -> wallets().getBalances(GetBalancesRequest.getDefaultInstance()).getBalances().getXmr());
    }

    /** The wallet's mnemonic seed. Keep it secret. */
    public String getXmrSeed() {
        return call(() -> wallets().getXmrSeed(GetXmrSeedRequest.getDefaultInstance()).getSeed());
    }

    public String getXmrPrimaryAddress() {
        return call(() -> wallets().getXmrPrimaryAddress(GetXmrPrimaryAddressRequest.getDefaultInstance())
                .getPrimaryAddress());
    }

    /** Creates a new subaddress to receive XMR. */
    public String getXmrNewSubaddress() {
        return call(() -> wallets().getXmrNewSubaddress(GetXmrNewSubaddressRequest.getDefaultInstance())
                .getSubaddress());
    }

    public List<XmrTx> getXmrTxs() {
        return call(() -> wallets().getXmrTxs(GetXmrTxsRequest.getDefaultInstance()).getTxsList());
    }

    /**
     * Creates (but does not broadcast) a transaction to the given destinations. Broadcast it
     * with {@link #relayXmrTxs(List)} using the returned tx's metadata.
     */
    public XmrTx createXmrTx(List<XmrDestination> destinations) {
        return call(() -> wallets().createXmrTx(CreateXmrTxRequest.newBuilder()
                .addAllDestinations(destinations).build()).getTx());
    }

    /** Creates (but does not broadcast) transactions sending all unlocked funds to an address. */
    public List<XmrTx> createXmrSweepTxs(String address) {
        return call(() -> wallets().createXmrSweepTxs(CreateXmrSweepTxsRequest.newBuilder()
                .setAddress(address).build()).getTxsList());
    }

    /** Broadcasts transactions created earlier, by their metadata; returns their hashes. */
    public List<String> relayXmrTxs(List<String> metadatas) {
        return call(() -> wallets().relayXmrTxs(RelayXmrTxsRequest.newBuilder()
                .addAllMetadatas(metadatas).build()).getHashesList());
    }

    public AddressBalanceInfo getAddressBalance(String address) {
        return call(() -> wallets().getAddressBalance(GetAddressBalanceRequest.newBuilder()
                .setAddress(address).build()).getAddressBalanceInfo());
    }

    public List<AddressBalanceInfo> getFundingAddresses() {
        return call(() -> wallets().getFundingAddresses(GetFundingAddressesRequest.getDefaultInstance())
                .getAddressBalanceInfoList());
    }

    public WalletHeight getWalletHeight() {
        GetWalletHeightReply reply = call(() -> wallets().getHeight(GetWalletHeightRequest.getDefaultInstance()));
        return new WalletHeight(reply.getHeight(), reply.getTargetHeight());
    }

    /** Current and target sync height of the wallet. */
    public record WalletHeight(long height, long targetHeight) {
        public boolean isSynced() {
            return targetHeight > 0 && height >= targetHeight;
        }
    }

    public void setWalletPassword(String password, String newPassword) {
        call(() -> wallets().setWalletPassword(SetWalletPasswordRequest.newBuilder()
                .setPassword(password).setNewPassword(newPassword).build()));
    }

    public void removeWalletPassword(String password) {
        call(() -> wallets().removeWalletPassword(RemoveWalletPasswordRequest.newBuilder()
                .setPassword(password).build()));
    }

    public void lockWallet() {
        call(() -> wallets().lockWallet(LockWalletRequest.getDefaultInstance()));
    }

    /** Unlocks the wallet for {@code timeoutSeconds} seconds. */
    public void unlockWallet(String password, long timeoutSeconds) {
        call(() -> wallets().unlockWallet(UnlockWalletRequest.newBuilder()
                .setPassword(password).setTimeout(timeoutSeconds).build()));
    }

    // ------------------------------------------------------------------------------------
    // Payment accounts
    // ------------------------------------------------------------------------------------

    public List<PaymentMethod> getPaymentMethods() {
        return call(() -> paymentAccounts().getPaymentMethods(GetPaymentMethodsRequest.getDefaultInstance())
                .getPaymentMethodsList());
    }

    public List<PaymentMethod> getCryptoPaymentMethods() {
        return call(() -> paymentAccounts().getCryptoCurrencyPaymentMethods(
                GetCryptoCurrencyPaymentMethodsRequest.getDefaultInstance()).getPaymentMethodsList());
    }

    public List<PaymentAccount> getPaymentAccounts() {
        return call(() -> paymentAccounts().getPaymentAccounts(GetPaymentAccountsRequest.getDefaultInstance())
                .getPaymentAccountsList());
    }

    /** The blank form to fill in for a payment method, e.g. {@code "SEPA"} or {@code "REVOLUT"}. */
    public PaymentAccountForm getPaymentAccountForm(String paymentMethodId) {
        return call(() -> paymentAccounts().getPaymentAccountForm(GetPaymentAccountFormRequest.newBuilder()
                .setPaymentMethodId(paymentMethodId).build()).getPaymentAccountForm());
    }

    /** The blank form for a payment method, as JSON. */
    public String getPaymentAccountFormAsJson(String paymentMethodId) {
        return call(() -> paymentAccounts().getPaymentAccountFormAsJson(
                GetPaymentAccountFormAsJsonRequest.newBuilder().setPaymentMethodId(paymentMethodId).build())
                .getPaymentAccountFormAsJson());
    }

    /** Creates a payment account from a filled-in form. */
    public PaymentAccount createPaymentAccount(PaymentAccountForm form) {
        return call(() -> paymentAccounts().createPaymentAccount(CreatePaymentAccountRequest.newBuilder()
                .setPaymentAccountForm(form).build()).getPaymentAccount());
    }

    /** Creates a payment account from a filled-in form in JSON. */
    public PaymentAccount createPaymentAccountFromJson(String formAsJson) {
        return call(() -> paymentAccounts().createPaymentAccount(CreatePaymentAccountRequest.newBuilder()
                .setPaymentAccountFormAsJson(formAsJson).build()).getPaymentAccount());
    }

    /** Creates a payment account to receive a cryptocurrency, e.g. {@code "BTC"}, at an address. */
    public PaymentAccount createCryptoPaymentAccount(String accountName, String currencyCode, String address) {
        return call(() -> paymentAccounts().createCryptoCurrencyPaymentAccount(
                CreateCryptoCurrencyPaymentAccountRequest.newBuilder()
                        .setAccountName(accountName)
                        .setCurrencyCode(currencyCode)
                        .setAddress(address)
                        .build()).getPaymentAccount());
    }

    public void deletePaymentAccount(String paymentAccountId) {
        call(() -> paymentAccounts().deletePaymentAccount(DeletePaymentAccountRequest.newBuilder()
                .setPaymentAccountId(paymentAccountId).build()));
    }

    /** Validates one field of a payment account form; throws {@link HavenoException} if invalid. */
    public void validateFormField(PaymentAccountForm form, PaymentAccountFormField.FieldId fieldId, String value) {
        call(() -> paymentAccounts().validateFormField(ValidateFormFieldRequest.newBuilder()
                .setForm(form).setFieldId(fieldId).setValue(value).build()));
    }

    // ------------------------------------------------------------------------------------
    // Offers
    // ------------------------------------------------------------------------------------

    /** Other users' offers for a currency, e.g. SELL offers in EUR. */
    public List<OfferInfo> getOffers(String currencyCode, OfferDirection direction) {
        return call(() -> offers().getOffers(GetOffersRequest.newBuilder()
                .setCurrencyCode(currencyCode)
                .setDirection(direction == null ? "" : direction.name())
                .build()).getOffersList());
    }

    /** Other users' offers for a currency, both directions. */
    public List<OfferInfo> getOffers(String currencyCode) {
        return getOffers(currencyCode, null);
    }

    /** Your own offers for a currency; pass null as direction for both, and "" as currency for all. */
    public List<OfferInfo> getMyOffers(String currencyCode, OfferDirection direction) {
        return call(() -> offers().getMyOffers(GetMyOffersRequest.newBuilder()
                .setCurrencyCode(currencyCode == null ? "" : currencyCode)
                .setDirection(direction == null ? "" : direction.name())
                .build()).getOffersList());
    }

    /** All of your own offers. */
    public List<OfferInfo> getMyOffers() {
        return getMyOffers("", null);
    }

    public OfferInfo getOffer(String offerId) {
        return call(() -> offers().getOffer(GetOfferRequest.newBuilder().setId(offerId).build()).getOffer());
    }

    public OfferInfo getMyOffer(String offerId) {
        return call(() -> offers().getMyOffer(GetMyOfferRequest.newBuilder().setId(offerId).build()).getOffer());
    }

    /** Posts a new offer. Funds for the offer are reserved from your wallet. */
    public OfferInfo postOffer(PostOfferParams params) {
        return call(() -> offers().postOffer(params.toRequest()).getOffer());
    }

    /**
     * Edits one of your offers. Build the request with the fields to change, e.g.
     * {@code EditOfferRequest.newBuilder().setOfferId(id).setUseMarketBasedPrice(true).setMarketPriceMarginPct(3)}.
     */
    public OfferInfo editOffer(EditOfferRequest request) {
        return call(() -> offers().editOffer(request).getOffer());
    }

    /** Takes an offer off the order book without cancelling it. */
    public void deactivateOffer(String offerId) {
        call(() -> offers().deactivateOffer(DeactivateOfferRequest.newBuilder().setOfferId(offerId).build()));
    }

    /** Puts a deactivated offer back on the order book. */
    public void activateOffer(String offerId) {
        call(() -> offers().activateOffer(ActivateOfferRequest.newBuilder().setOfferId(offerId).build()));
    }

    /** Cancels one of your offers and releases its reserved funds. */
    public void cancelOffer(String offerId) {
        call(() -> offers().cancelOffer(CancelOfferRequest.newBuilder().setId(offerId).build()));
    }

    // ------------------------------------------------------------------------------------
    // Trades
    // ------------------------------------------------------------------------------------

    /**
     * Takes an offer and starts a trade.
     *
     * @param amount    amount to trade in atomic units, or 0 for the offer's full amount
     * @param challenge the offer's challenge for private offers, otherwise null
     * @throws HavenoException if the offer can't be taken (the daemon's reason is in the message)
     */
    public TradeInfo takeOffer(String offerId, String paymentAccountId, long amount, String challenge) {
        TakeOfferRequest.Builder request = TakeOfferRequest.newBuilder()
                .setOfferId(offerId)
                .setPaymentAccountId(paymentAccountId)
                .setAmount(amount);
        if (challenge != null) {
            request.setChallenge(challenge);
        }
        TakeOfferReply reply = call(() -> trades().takeOffer(request.build()));
        if (!reply.hasTrade()) {
            String reason = reply.hasFailureReason()
                    ? reply.getFailureReason().getDescription()
                    : "offer could not be taken";
            throw new HavenoException(reason, io.grpc.Status.Code.FAILED_PRECONDITION, null);
        }
        return reply.getTrade();
    }

    /** Takes the full amount of a public offer. */
    public TradeInfo takeOffer(String offerId, String paymentAccountId) {
        return takeOffer(offerId, paymentAccountId, 0, null);
    }

    public TradeInfo getTrade(String tradeId) {
        return call(() -> trades().getTrade(GetTradeRequest.newBuilder().setTradeId(tradeId).build()).getTrade());
    }

    /** Your open trades. */
    public List<TradeInfo> getTrades() {
        return getTrades(GetTradesRequest.Category.OPEN);
    }

    /** Your trades in a category: OPEN, CLOSED or FAILED. */
    public List<TradeInfo> getTrades(GetTradesRequest.Category category) {
        return call(() -> trades().getTrades(GetTradesRequest.newBuilder().setCategory(category).build())
                .getTradesList());
    }

    /** As buyer: confirm you have sent the payment. */
    public void confirmPaymentSent(String tradeId) {
        call(() -> trades().confirmPaymentSent(ConfirmPaymentSentRequest.newBuilder().setTradeId(tradeId).build()));
    }

    /** As seller: confirm you have received the payment, releasing the XMR to the buyer. */
    public void confirmPaymentReceived(String tradeId) {
        call(() -> trades().confirmPaymentReceived(ConfirmPaymentReceivedRequest.newBuilder()
                .setTradeId(tradeId).build()));
    }

    /** Marks a finished trade as completed and moves it to your trade history. */
    public void completeTrade(String tradeId) {
        call(() -> trades().completeTrade(CompleteTradeRequest.newBuilder().setTradeId(tradeId).build()));
    }

    /** Withdraws a trade's payout to an external address. */
    public void withdrawFunds(String tradeId, String address, String memo) {
        call(() -> trades().withdrawFunds(WithdrawFundsRequest.newBuilder()
                .setTradeId(tradeId)
                .setAddress(address)
                .setMemo(memo == null ? "" : memo)
                .build()));
    }

    public List<protobuf.ChatMessage> getChatMessages(String tradeId) {
        return call(() -> trades().getChatMessages(GetChatMessagesRequest.newBuilder().setTradeId(tradeId).build())
                .getMessageList());
    }

    /** Sends a chat message to your trading peer. */
    public void sendChatMessage(String tradeId, String message) {
        call(() -> trades().sendChatMessage(SendChatMessageRequest.newBuilder()
                .setTradeId(tradeId).setMessage(message).build()));
    }

    // ------------------------------------------------------------------------------------
    // Disputes
    // ------------------------------------------------------------------------------------

    public Dispute getDispute(String tradeId) {
        return call(() -> disputes().getDispute(GetDisputeRequest.newBuilder().setTradeId(tradeId).build())
                .getDispute());
    }

    public List<Dispute> getDisputes() {
        return call(() -> disputes().getDisputes(GetDisputesRequest.getDefaultInstance()).getDisputesList());
    }

    /** Opens a dispute for a trade, bringing in an arbitrator. */
    public void openDispute(String tradeId) {
        call(() -> disputes().openDispute(OpenDisputeRequest.newBuilder().setTradeId(tradeId).build()));
    }

    /**
     * Arbitrators only: resolves a dispute.
     *
     * @param customPayoutAmount payout to the winner in atomic units, or 0 for the default
     */
    public void resolveDispute(String tradeId, DisputeResult.Winner winner, DisputeResult.Reason reason,
                               String summaryNotes, long customPayoutAmount) {
        call(() -> disputes().resolveDispute(ResolveDisputeRequest.newBuilder()
                .setTradeId(tradeId)
                .setWinner(winner)
                .setReason(reason)
                .setSummaryNotes(summaryNotes)
                .setCustomPayoutAmount(customPayoutAmount)
                .build()));
    }

    public void sendDisputeChatMessage(String disputeId, String message, List<Attachment> attachments) {
        call(() -> disputes().sendDisputeChatMessage(SendDisputeChatMessageRequest.newBuilder()
                .setDisputeId(disputeId)
                .setMessage(message)
                .addAllAttachments(attachments == null ? List.of() : attachments)
                .build()));
    }

    /** Registers this node as a dispute agent (e.g. "arbitrator"); needs a registration key. */
    public void registerDisputeAgent(String disputeAgentType, String registrationKey) {
        call(() -> stub(DisputeAgentsGrpc.newBlockingStub(channel)).registerDisputeAgent(
                RegisterDisputeAgentRequest.newBuilder()
                        .setDisputeAgentType(disputeAgentType)
                        .setRegistrationKey(registrationKey)
                        .build()));
    }

    public void unregisterDisputeAgent(String disputeAgentType) {
        call(() -> stub(DisputeAgentsGrpc.newBlockingStub(channel)).unregisterDisputeAgent(
                UnregisterDisputeAgentRequest.newBuilder().setDisputeAgentType(disputeAgentType).build()));
    }

    // ------------------------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------------------------

    /** The network-wide alert currently in effect, if any. */
    public Alert getAlert() {
        return call(() -> stub(NotificationsGrpc.newBlockingStub(channel))
                .getAlert(GetAlertRequest.getDefaultInstance()).getAlert());
    }

    /**
     * Listens for daemon notifications: trade updates, chat messages, alerts and errors.
     * Keep-alive pings are filtered out. Callbacks run on a gRPC thread, so keep them short.
     *
     * <pre>{@code
     * Subscription sub = haveno.subscribeNotifications(
     *         n -> System.out.println(n.getType() + ": " + n.getTitle()),
     *         err -> System.err.println("notification stream ended: " + err.getMessage()));
     * ...
     * sub.close();
     * }</pre>
     *
     * @param onNotification called for each notification
     * @param onError        called once if the stream fails (daemon stopped, wrong password, ...);
     *                       may be null
     */
    public Subscription subscribeNotifications(Consumer<NotificationMessage> onNotification,
                                               Consumer<HavenoException> onError) {
        Objects.requireNonNull(onNotification, "onNotification");
        NotificationStream stream = new NotificationStream(onNotification, onError);
        NotificationsGrpc.newStub(channel).registerNotificationListener(
                RegisterNotificationListenerRequest.getDefaultInstance(), stream);
        return stream;
    }

    private static final class NotificationStream
            implements ClientResponseObserver<RegisterNotificationListenerRequest, NotificationMessage>, Subscription {

        private final Consumer<NotificationMessage> onNotification;
        private final Consumer<HavenoException> onError;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private volatile ClientCallStreamObserver<RegisterNotificationListenerRequest> call;

        NotificationStream(Consumer<NotificationMessage> onNotification, Consumer<HavenoException> onError) {
            this.onNotification = onNotification;
            this.onError = onError;
        }

        @Override
        public void beforeStart(ClientCallStreamObserver<RegisterNotificationListenerRequest> requestStream) {
            this.call = requestStream;
        }

        @Override
        public void onNext(NotificationMessage message) {
            if (message.getType() != NotificationMessage.NotificationType.KEEP_ALIVE) {
                onNotification.accept(message);
            }
        }

        @Override
        public void onError(Throwable t) {
            boolean wasActive = active.getAndSet(false);
            if (!wasActive || onError == null) {
                return; // closed by us, or nobody to tell
            }
            onError.accept(t instanceof StatusRuntimeException sre
                    ? HavenoException.from(sre)
                    : new HavenoException(String.valueOf(t.getMessage()), io.grpc.Status.Code.UNKNOWN, t));
        }

        @Override
        public void onCompleted() {
            active.set(false);
        }

        @Override
        public void close() {
            if (active.getAndSet(false) && call != null) {
                call.cancel("subscription closed by client", null);
            }
        }

        @Override
        public boolean isActive() {
            return active.get();
        }
    }

    // ------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------

    private AccountGrpc.AccountBlockingStub account() {
        return stub(AccountGrpc.newBlockingStub(channel));
    }

    private XmrConnectionsGrpc.XmrConnectionsBlockingStub connections() {
        return stub(XmrConnectionsGrpc.newBlockingStub(channel));
    }

    private XmrNodeGrpc.XmrNodeBlockingStub xmrNode() {
        return stub(XmrNodeGrpc.newBlockingStub(channel));
    }

    private PriceGrpc.PriceBlockingStub price() {
        return stub(PriceGrpc.newBlockingStub(channel));
    }

    private WalletsGrpc.WalletsBlockingStub wallets() {
        return stub(WalletsGrpc.newBlockingStub(channel));
    }

    private PaymentAccountsGrpc.PaymentAccountsBlockingStub paymentAccounts() {
        return stub(PaymentAccountsGrpc.newBlockingStub(channel));
    }

    private OffersGrpc.OffersBlockingStub offers() {
        return stub(OffersGrpc.newBlockingStub(channel));
    }

    private TradesGrpc.TradesBlockingStub trades() {
        return stub(TradesGrpc.newBlockingStub(channel));
    }

    private DisputesGrpc.DisputesBlockingStub disputes() {
        return stub(DisputesGrpc.newBlockingStub(channel));
    }

    /** Applies the per-call timeout to a unary stub. */
    private <S extends AbstractStub<S>> S stub(S stub) {
        return stub.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Streaming calls (backups) can take longer than one unary call; allow 10x the timeout. */
    private <S extends AbstractStub<S>> S streamingStub(S stub) {
        return stub.withDeadlineAfter(timeout.toMillis() * 10, TimeUnit.MILLISECONDS);
    }

    private static <T> T call(Supplier<T> grpcCall) {
        try {
            return grpcCall.get();
        } catch (StatusRuntimeException e) {
            throw HavenoException.from(e);
        }
    }

    // ------------------------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------------------------

    public static final class Builder {
        private String host = "localhost";
        private int port = DEFAULT_PORT;
        private String password;
        private boolean useTls;
        private Duration timeout = Duration.ofMinutes(2);
        private Channel channel;

        private Builder() {
        }

        /** Daemon host; defaults to {@code localhost}. */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /** Daemon API port ({@code --apiPort}); defaults to 9998. */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /** Required. The daemon's API password ({@code --apiPassword}). */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /**
         * Use TLS. The Haveno daemon serves plaintext gRPC, so leave this off unless the daemon
         * is behind a TLS-terminating proxy. Over a network, don't send the password in the
         * clear: use TLS, an SSH tunnel, or the daemon's Tor hidden service.
         */
        public Builder useTls(boolean useTls) {
            this.useTls = useTls;
            return this;
        }

        /** Maximum time for each call; defaults to 2 minutes (taking an offer can be slow). */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Use a channel you created yourself (e.g. with custom TLS or a proxy). The password
         * is still added to every call. You remain responsible for shutting the channel down.
         */
        public Builder channel(Channel channel) {
            this.channel = channel;
            return this;
        }

        public HavenoClient build() {
            Objects.requireNonNull(password, "password is required: the daemon's --apiPassword");
            Objects.requireNonNull(timeout, "timeout");
            PasswordInterceptor auth = new PasswordInterceptor(password);
            if (channel != null) {
                return new HavenoClient(ClientInterceptors.intercept(channel, auth), null, timeout);
            }
            ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(host, port)
                    .intercept(auth)
                    .maxInboundMessageSize(64 * 1024 * 1024);
            if (useTls) {
                builder.useTransportSecurity();
            } else {
                builder.usePlaintext();
            }
            ManagedChannel managed = builder.build();
            return new HavenoClient(managed, managed, timeout);
        }
    }
}
