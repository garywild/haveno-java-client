# Haveno Java Client

A Java client library for the [Haveno](https://github.com/haveno-dex/haveno) daemon's gRPC API. It is the Java counterpart of the official TypeScript client, [haveno-ts](https://github.com/haveno-dex/haveno-ts).

You can use it to build trading bots, dashboards or other tools on top of a running Haveno node. It covers offers, trades, wallet, prices, payment accounts, Monero node connections, disputes, account backup and restore, and live notifications.

## Requirements

- **Java 17** or newer, and **Maven 3.8** or newer.
- **A running Haveno daemon** with an API password, for example:
  ```
  ./haveno-daemon --apiPassword=apitest --apiPort=9998 ...
  ```

## Install

### From Maven Central

```xml
<dependency>
    <groupId>io.github.garywild</groupId>
    <artifactId>haveno-java-client</artifactId>
    <version>0.1.0</version>
</dependency>
```

Gradle: `implementation("io.github.garywild:haveno-java-client:0.1.0")`

### From JitPack

JitPack builds the library straight from a GitHub tag. Add the JitPack repository and the dependency:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.garywild</groupId>
    <artifactId>haveno-java-client</artifactId>
    <version>v0.1.0</version>
</dependency>
```

[![](https://jitpack.io/v/garywild/haveno-java-client.svg)](https://jitpack.io/#garywild/haveno-java-client)

### From source

```
git clone https://github.com/garywild/haveno-java-client.git
cd haveno-java-client
mvn install
```

To publish a new version, see [RELEASING.md](RELEASING.md).

## Quick start

```java
import haveno.client.*;
import haveno.proto.grpc.*;

try (HavenoClient haveno = HavenoClient.builder()
        .host("localhost")
        .port(9998)
        .password("apitest")
        .build()) {

    System.out.println("Haveno " + haveno.getVersion());
    System.out.println("XMR/EUR " + haveno.getPrice("EUR"));

    for (OfferInfo offer : haveno.getOffers("EUR", OfferDirection.SELL)) {
        System.out.println(offer.getId() + "  "
                + XmrUnits.toXmr(offer.getAmount()) + " XMR @ " + offer.getPrice());
    }
}
```

`src/test/java/haveno/client/Example.java` contains a runnable version of this example.

## Usage

### XMR amounts

The API uses **atomic units**, where 1 XMR = 10¹² atomic units. `XmrUnits` converts between the two:

```java
long amount = XmrUnits.toAtomicUnits("0.5");    // 500000000000
BigDecimal xmr = XmrUnits.toXmr(balance);       // 0.5
```

### Posting an offer

```java
PaymentAccount account = haveno.getPaymentAccounts().get(0);

OfferInfo offer = haveno.postOffer(PostOfferParams.builder()
        .direction(OfferDirection.SELL)          // sell XMR
        .currencyCode("EUR")
        .amount(XmrUnits.toAtomicUnits("1.0"))
        .minAmount(XmrUnits.toAtomicUnits("0.25"))
        .marketPriceMarginPct(2.0)               // 2% above market, or .fixedPrice("165.00")
        .securityDepositPct(0.15)
        .paymentAccountId(account.getId())
        .build());

haveno.deactivateOffer(offer.getId());  // remove from the order book
haveno.activateOffer(offer.getId());
haveno.cancelOffer(offer.getId());      // cancel and release reserved funds
```

### Taking an offer and running a trade

```java
TradeInfo trade = haveno.takeOffer(offerId, account.getId());   // full amount

// buyer, after paying:
haveno.confirmPaymentSent(trade.getTradeId());
// seller, after receiving payment:
haveno.confirmPaymentReceived(trade.getTradeId());

haveno.getTrade(trade.getTradeId()).getPhase();
haveno.sendChatMessage(trade.getTradeId(), "Payment sent!");
haveno.completeTrade(trade.getTradeId());
```

### Notifications

```java
Subscription sub = haveno.subscribeNotifications(
        n -> {
            if (n.getType() == NotificationMessage.NotificationType.TRADE_UPDATE) {
                System.out.println("Trade " + n.getTrade().getShortId() + " -> " + n.getTrade().getPhase());
            }
        },
        error -> System.err.println("Notification stream ended: " + error.getMessage()));

// later
sub.close();
```

### Wallet

```java
XmrBalanceInfo b = haveno.getBalances();
XmrUnits.toXmr(b.getAvailableBalance());
haveno.getXmrNewSubaddress();                     // address to fund the wallet

XmrTx tx = haveno.createXmrTx(List.of(XmrDestination.newBuilder()
        .setAddress(address).setAmount(String.valueOf(XmrUnits.toAtomicUnits("0.1"))).build()));
haveno.relayXmrTxs(List.of(tx.getMetadata()));    // broadcast
```

### Errors

Every call throws `HavenoException`, an unchecked exception. Its message contains the daemon's own error text, and `getCode()` returns the gRPC status code:

```java
try {
    haveno.getOffer("does-not-exist");
} catch (HavenoException e) {
    if (e.isUnavailable())          { /* daemon not running / wrong host or port */ }
    else if (e.isUnauthenticated()) { /* wrong API password */ }
    else                            { System.err.println(e.getMessage()); }
}
```

### Client options

| Builder method | Default | Notes |
|---|---|---|
| `host(String)` | `localhost` | |
| `port(int)` | `9998` | The daemon's `--apiPort` |
| `password(String)` | *(required)* | The daemon's `--apiPassword` |
| `timeout(Duration)` | 2 minutes | The limit for each call; taking an offer can be slow |
| `useTls(boolean)` | `false` | The daemon serves plaintext gRPC |
| `channel(Channel)` | — | Bring your own gRPC channel, for a proxy or custom TLS |

> **Security:** the API password is sent with every call. Over plaintext, anyone on the network path can read it. Keep the daemon on `localhost`, or reach it through an SSH tunnel, Tor, or a TLS proxy.

### Anything not wrapped

All the generated stubs are available. Use the authenticated channel with them directly:

```java
OffersGrpc.OffersBlockingStub offers = OffersGrpc.newBlockingStub(haveno.getChannel());
```

## API coverage

| Haveno service | Client methods |
|---|---|
| GetVersion, Help, ShutdownServer | `getVersion`, `getMethodHelp`, `stopDaemon` |
| Account | `accountExists`, `isAccountOpen`, `createAccount`, `createAccountFromSeed`, `openAccount`, `isAppInitialized`, `changePassword`, `closeAccount`, `deleteAccount`, `backupAccount`, `restoreAccount` |
| XmrConnections | `add/remove/get/setMoneroConnection`, `getMoneroConnections`, `checkMoneroConnection`, `getBestMoneroConnection`, `get/setAutoSwitch` |
| XmrNode | `isMoneroNodeOnline`, `getMoneroNodeSettings`, `startMoneroNode`, `stopMoneroNode` |
| Price, GetTradeStatistics | `getPrice`, `getPrices`, `getMarketDepth`, `getTradeStatistics` |
| Wallets | `getBalances`, `getXmrSeed`, `getXmrPrimaryAddress`, `getXmrNewSubaddress`, `getXmrTxs`, `createXmrTx`, `createXmrSweepTxs`, `relayXmrTxs`, `getAddressBalance`, `getFundingAddresses`, `getWalletHeight`, wallet password and lock/unlock |
| PaymentAccounts | `getPaymentMethods`, `getCryptoPaymentMethods`, `getPaymentAccounts`, `getPaymentAccountForm(AsJson)`, `createPaymentAccount(FromJson)`, `createCryptoPaymentAccount`, `deletePaymentAccount`, `validateFormField` |
| Offers | `getOffers`, `getMyOffers`, `getOffer`, `getMyOffer`, `postOffer`, `editOffer`, `activateOffer`, `deactivateOffer`, `cancelOffer` |
| Trades | `takeOffer`, `getTrade`, `getTrades`, `confirmPaymentSent`, `confirmPaymentReceived`, `completeTrade`, `withdrawFunds`, `getChatMessages`, `sendChatMessage` |
| Disputes, DisputeAgents | `getDispute`, `getDisputes`, `openDispute`, `resolveDispute`, `sendDisputeChatMessage`, `register/unregisterDisputeAgent` |
| Notifications | `subscribeNotifications`, `getAlert` |

## Keeping up with Haveno

`src/main/proto/grpc.proto` and `pb.proto` are copied from [haveno-dex/haveno](https://github.com/haveno-dex/haveno/tree/master/proto/src/main/proto) at commit `8781855`. If the daemon's API changes, copy the newer files in and run `mvn verify`. Also keep `grpc.version` and `protobuf.version` in `pom.xml` in line with Haveno's `gradle/libs.versions.toml`.

## Tests

```
mvn test
```

The tests run the client against an in-process fake daemon, so no real Haveno node is needed. The fake daemon checks the API password the same way Haveno does. GitHub Actions runs the tests on every push.

## License

[AGPL-3.0](LICENSE). This is the same license as Haveno, whose protocol definitions are included here.
