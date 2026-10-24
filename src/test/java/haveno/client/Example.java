package haveno.client;

import haveno.proto.grpc.OfferInfo;
import haveno.proto.grpc.XmrBalanceInfo;

/**
 * Small runnable example against a real daemon (not run by the tests).
 *
 * <pre>
 * mvn -q test-compile exec:java -Dexec.mainClass=haveno.client.Example \
 *     -Dexec.classpathScope=test -Dexec.args="localhost 9998 apitest EUR"
 * </pre>
 */
public final class Example {

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : HavenoClient.DEFAULT_PORT;
        String password = args.length > 2 ? args[2] : "apitest";
        String currency = args.length > 3 ? args[3] : "EUR";

        try (HavenoClient haveno = HavenoClient.builder().host(host).port(port).password(password).build()) {
            System.out.println("Haveno daemon " + haveno.getVersion());
            System.out.printf("XMR/%s market price: %.2f%n", currency, haveno.getPrice(currency));

            XmrBalanceInfo balances = haveno.getBalances();
            System.out.println("Available balance: " + XmrUnits.toXmr(balances.getAvailableBalance()) + " XMR");

            for (OfferDirection direction : OfferDirection.values()) {
                System.out.println(direction + " offers in " + currency + ":");
                for (OfferInfo offer : haveno.getOffers(currency, direction)) {
                    System.out.printf("  %s  %s XMR @ %s %s  (%s)%n",
                            offer.getId(),
                            XmrUnits.toXmr(offer.getAmount()),
                            offer.getPrice(),
                            currency,
                            offer.getPaymentMethodShortName());
                }
            }
        } catch (HavenoException e) {
            if (e.isUnavailable()) {
                System.err.println("Can't reach the Haveno daemon at " + host + ":" + port + ". Is it running?");
            } else if (e.isUnauthenticated()) {
                System.err.println("Wrong API password.");
            } else {
                System.err.println("Haveno error: " + e.getMessage());
            }
            System.exit(1);
        }
    }
}
