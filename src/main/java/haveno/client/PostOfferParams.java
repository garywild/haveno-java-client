package haveno.client;

import haveno.proto.grpc.PostOfferRequest;

import java.util.Objects;

/**
 * Parameters for posting a new offer with {@link HavenoClient#postOffer(PostOfferParams)}.
 *
 * <p>Use either a fixed price ({@link Builder#fixedPrice(String)}) or a market-based price
 * ({@link Builder#marketPriceMarginPct(double)}). Amounts are in XMR atomic units; see
 * {@link XmrUnits}.
 *
 * <pre>{@code
 * PostOfferParams params = PostOfferParams.builder()
 *         .direction(OfferDirection.SELL)
 *         .currencyCode("EUR")
 *         .amount(XmrUnits.toAtomicUnits("1.5"))
 *         .marketPriceMarginPct(2.0)      // 2% above market
 *         .securityDepositPct(0.15)
 *         .paymentAccountId(accountId)
 *         .build();
 * }</pre>
 */
public final class PostOfferParams {

    private final PostOfferRequest request;

    private PostOfferParams(PostOfferRequest request) {
        this.request = request;
    }

    public static Builder builder() {
        return new Builder();
    }

    PostOfferRequest toRequest() {
        return request;
    }

    public static final class Builder {
        private OfferDirection direction;
        private String currencyCode;
        private Long amount;
        private long minAmount;
        private String price;
        private Double marketPriceMarginPct;
        private double securityDepositPct = 0.15;
        private String triggerPrice = "";
        private boolean reserveExactAmount;
        private String paymentAccountId;
        private boolean isPrivateOffer;
        private boolean buyerAsTakerWithoutDeposit;
        private String extraInfo = "";
        private String sourceOfferId = "";

        private Builder() {
        }

        /** Required. BUY to buy XMR, SELL to sell XMR. */
        public Builder direction(OfferDirection direction) {
            this.direction = direction;
            return this;
        }

        /** Required. The counter currency, e.g. "EUR", "USD", "BTC". */
        public Builder currencyCode(String currencyCode) {
            this.currencyCode = currencyCode;
            return this;
        }

        /** Required. Offer amount in XMR atomic units. */
        public Builder amount(long amount) {
            this.amount = amount;
            return this;
        }

        /** Optional minimum trade amount in atomic units; defaults to the full amount. */
        public Builder minAmount(long minAmount) {
            this.minAmount = minAmount;
            return this;
        }

        /** Fixed price per XMR in the counter currency, e.g. "150.50". */
        public Builder fixedPrice(String price) {
            this.price = price;
            return this;
        }

        /** Market-based price, as a percent margin over/under market (e.g. 2.0 or -1.5). */
        public Builder marketPriceMarginPct(double marginPct) {
            this.marketPriceMarginPct = marginPct;
            return this;
        }

        /** Security deposit as a fraction of the trade amount; defaults to 0.15 (15%). */
        public Builder securityDepositPct(double securityDepositPct) {
            this.securityDepositPct = securityDepositPct;
            return this;
        }

        /** Optional price at which a market-based offer is deactivated automatically. */
        public Builder triggerPrice(String triggerPrice) {
            this.triggerPrice = triggerPrice;
            return this;
        }

        /** Reserve exactly the offer amount by splitting an output (costs a tx and wait). */
        public Builder reserveExactAmount(boolean reserveExactAmount) {
            this.reserveExactAmount = reserveExactAmount;
            return this;
        }

        /** Required. ID of the payment account to receive/send the counter currency with. */
        public Builder paymentAccountId(String paymentAccountId) {
            this.paymentAccountId = paymentAccountId;
            return this;
        }

        /** Make the offer private; takers then need the offer's challenge to take it. */
        public Builder privateOffer(boolean isPrivateOffer) {
            this.isPrivateOffer = isPrivateOffer;
            return this;
        }

        /** Allow the buyer to take this offer without a security deposit. */
        public Builder buyerAsTakerWithoutDeposit(boolean value) {
            this.buyerAsTakerWithoutDeposit = value;
            return this;
        }

        /** Optional free-text terms shown to takers. */
        public Builder extraInfo(String extraInfo) {
            this.extraInfo = extraInfo;
            return this;
        }

        /** Optional ID of an existing offer to clone (shares its reserved funds). */
        public Builder sourceOfferId(String sourceOfferId) {
            this.sourceOfferId = sourceOfferId;
            return this;
        }

        public PostOfferParams build() {
            Objects.requireNonNull(direction, "direction is required");
            Objects.requireNonNull(currencyCode, "currencyCode is required");
            Objects.requireNonNull(amount, "amount is required");
            Objects.requireNonNull(paymentAccountId, "paymentAccountId is required");
            if ((price == null) == (marketPriceMarginPct == null)) {
                throw new IllegalArgumentException(
                        "set exactly one of fixedPrice(...) or marketPriceMarginPct(...)");
            }
            if (amount <= 0) {
                throw new IllegalArgumentException("amount must be positive");
            }
            if (minAmount < 0 || minAmount > amount) {
                throw new IllegalArgumentException("minAmount must be between 0 and amount");
            }
            boolean marketBased = marketPriceMarginPct != null;
            PostOfferRequest request = PostOfferRequest.newBuilder()
                    .setDirection(direction.name())
                    .setCurrencyCode(currencyCode)
                    .setAmount(amount)
                    .setMinAmount(minAmount == 0 ? amount : minAmount)
                    .setUseMarketBasedPrice(marketBased)
                    .setPrice(marketBased ? "" : price)
                    .setMarketPriceMarginPct(marketBased ? marketPriceMarginPct : 0)
                    .setSecurityDepositPct(securityDepositPct)
                    .setTriggerPrice(triggerPrice)
                    .setReserveExactAmount(reserveExactAmount)
                    .setPaymentAccountId(paymentAccountId)
                    .setIsPrivateOffer(isPrivateOffer)
                    .setBuyerAsTakerWithoutDeposit(buyerAsTakerWithoutDeposit)
                    .setExtraInfo(extraInfo)
                    .setSourceOfferId(sourceOfferId)
                    .build();
            return new PostOfferParams(request);
        }
    }
}
