package io.cellophane.server.rules;

import static org.assertj.core.api.Assertions.assertThat;

import io.cellophane.smpp.CommandStatus;
import io.cellophane.smpp.receipt.DeliveryReceipt;

import java.time.Duration;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class RuleSetTest {

    private static RuleContext ctx(String account, String from, String to, String text) {
        return new RuleContext(account, "s1", from, to, text);
    }

    @Test
    void firstMatchWinsAndDefaultAppliesOtherwise() {
        RuleSet set = RulesYaml.parse(RulesYamlTest.README_EXAMPLE);

        assertThat(set.decide(ctx("app", "MyApp", "8801711111111", "hello")).rule()).isEqualTo("rule 1");
        assertThat(set.decide(ctx("app", "MyApp", "8801911111111", "hello")).rule()).isEqualTo("rule 2");
        assertThat(set.decide(ctx("app", "MyApp", "8801711111111", "SPAM offer")).rule())
                .as("earlier rule on the recipient wins over the spam rule").isEqualTo("rule 1");
        assertThat(set.decide(ctx("app", "MyApp", "15551234567", "cheap Spam")).action())
                .isEqualTo(Reject.with(CommandStatus.ESME_RINVDSTADR));
        assertThat(set.decide(ctx("quiet", "Bank", "1", "x")).rule()).isEqualTo("silent");
        assertThat(set.decide(ctx("quiet2", "Bank", "1", "x")).rule()).as("account matches whole system_id")
                .isEqualTo("default");
        assertThat(set.decide(ctx("quiet", "MyBank", "1", "x")).rule()).as("from is anchored by the pattern")
                .isEqualTo("default");
        Decision fallback = set.decide(ctx("app", "A", "1", null));
        assertThat(fallback.rule()).isEqualTo("default");
        assertThat(fallback.action()).isEqualTo(Accept.receipt(DeliveryReceipt.State.DELIVERED,
                Duration.ofMillis(500)));
    }

    @Test
    void gatesApplyTheirEffectAndFallThrough() {
        RuleSet set = RulesYaml.parse("""
                rules:
                  - name: slow
                    match: { account: slow }
                    latency: 2s
                  - name: flaky-link
                    match: { account: slow }
                    disconnect: { after: 3 }
                  - name: limited
                    match: { to: "^88015" }
                    throttle: { tps: 5, then: ESME_RMSGQFUL }
                  - name: ok
                    match: { to: "^8801" }
                    accept: { dlr: DELIVRD }
                """);
        Gates never = Gates.NONE;
        Gates always = new Gates() {
            @Override
            public boolean overThrottle(Rule rule, Throttle throttle, RuleContext ctx) {
                return true;
            }

            @Override
            public boolean disconnectDue(Rule rule, Disconnect disconnect, RuleContext ctx) {
                return true;
            }
        };

        Decision under = set.decide(ctx("slow", "A", "8801511111111", "x"), never);
        assertThat(under.rule()).isEqualTo("ok");
        assertThat(under.action()).isEqualTo(Accept.receipt(DeliveryReceipt.State.DELIVERED, Duration.ZERO));
        assertThat(under.latency()).isEqualTo(Duration.ofSeconds(2));
        assertThat(under.disconnect()).isFalse();
        assertThat(under.path()).containsExactly("slow", "flaky-link", "limited", "ok");

        Decision over = set.decide(ctx("slow", "A", "8801511111111", "x"), always);
        assertThat(over.rule()).isEqualTo("limited");
        assertThat(over.action()).isEqualTo(Reject.with(CommandStatus.ESME_RMSGQFUL));
        assertThat(over.latency()).isEqualTo(Duration.ofSeconds(2));
        assertThat(over.disconnect()).isTrue();
        assertThat(over.path()).containsExactly("slow", "flaky-link", "limited");

        Decision plain = set.decide(ctx("app", "A", "15551234567", "x"), always);
        assertThat(plain.rule()).isEqualTo("default");
        assertThat(plain.latency()).isZero();
        assertThat(plain.disconnect()).isFalse();
        assertThat(plain.path()).containsExactly("default");
    }

    @Test
    void nullTextNeverMatchesATextRule() {
        Match textRule = new Match(null, null, null, Pattern.compile(".*"));

        assertThat(textRule.matches(ctx("a", "b", "c", null))).isFalse();
        assertThat(textRule.matches(ctx("a", "b", "c", ""))).isTrue();
        assertThat(Match.ANY.matches(ctx(null, null, null, null))).isTrue();
    }

    @Test
    void picksOutcomesByWeight() {
        Accept accept = new Accept(List.of(
                new Accept.Outcome(DeliveryReceipt.State.DELIVERED, Delay.NONE, 80),
                new Accept.Outcome(DeliveryReceipt.State.UNDELIVERABLE, Delay.NONE, 20)));

        assertThat(accept.pick(fixed(0.0)).state()).isEqualTo(DeliveryReceipt.State.DELIVERED);
        assertThat(accept.pick(fixed(0.79)).state()).isEqualTo(DeliveryReceipt.State.DELIVERED);
        assertThat(accept.pick(fixed(0.81)).state()).isEqualTo(DeliveryReceipt.State.UNDELIVERABLE);
        assertThat(accept.pick(fixed(0.999)).state()).isEqualTo(DeliveryReceipt.State.UNDELIVERABLE);

        int delivered = 0;
        RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
        for (int i = 0; i < 10_000; i++) {
            if (accept.pick(random).state() == DeliveryReceipt.State.DELIVERED) {
                delivered++;
            }
        }
        assertThat(delivered).isBetween(7_700, 8_300);
    }

    @Test
    void picksDelaysWithinTheRange() {
        Delay range = new Delay(Duration.ofSeconds(1), Duration.ofSeconds(5));
        RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
        for (int i = 0; i < 1_000; i++) {
            assertThat(range.pick(random)).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(5));
        }
        assertThat(Delay.fixed(Duration.ofMillis(7)).pick(random)).isEqualTo(Duration.ofMillis(7));
    }

    @Test
    void engineKeepsOldRulesWhenNewOnesAreInvalid() {
        RuleEngine engine = RuleEngine.fromYaml("rules:\n  - reject: ESME_RMSGQFUL");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> engine.load("rules:\n  - nope: 1"))
                .isInstanceOf(RulesException.class);

        assertThat(engine.decide(ctx("a", "b", "c", "d")).action()).isEqualTo(Reject.with(CommandStatus.ESME_RMSGQFUL));
        assertThat(engine.yaml()).contains("ESME_RMSGQFUL");
        assertThat(new RuleEngine(RuleSet.DEFAULT).yaml()).contains("default:");
    }

    private static RandomGenerator fixed(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }
}
