package io.cellophane.server.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cellophane.smpp.CommandStatus;
import io.cellophane.smpp.receipt.DeliveryReceipt;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RulesYamlTest {

    static final String README_EXAMPLE = """
            rules:
              - match: { to: "^88017" }          # one operator delivers fine
                accept: { dlr: DELIVRD, after: 2s }

              - match: { to: "^88019" }          # another one is flaky today
                accept:
                  dlr: [ { DELIVRD: 80%, after: 3s }, { UNDELIV: 20%, after: 30s } ]

              - match: { text: "(?i)spam" }
                reject: ESME_RINVDSTADR

              - name: silent
                match: { account: "quiet", from: "^Bank$" }
                accept:

              - default:
                  accept: { dlr: DELIVRD, after: 500ms }
            """;

    @Test
    void parsesTheReadmeExample() {
        RuleSet set = RulesYaml.parse(README_EXAMPLE);

        assertThat(set.rules()).hasSize(4);
        assertThat(set.rules().get(0).name()).isEqualTo("rule 1");
        assertThat(set.rules().get(0).match().to().pattern()).isEqualTo("^88017");
        assertThat(set.rules().get(0).action()).isEqualTo(Accept.receipt(DeliveryReceipt.State.DELIVERED,
                Duration.ofSeconds(2)));

        Accept flaky = (Accept) set.rules().get(1).action();
        assertThat(flaky.outcomes()).containsExactly(
                new Accept.Outcome(DeliveryReceipt.State.DELIVERED, Delay.fixed(Duration.ofSeconds(3)), 80),
                new Accept.Outcome(DeliveryReceipt.State.UNDELIVERABLE, Delay.fixed(Duration.ofSeconds(30)), 20));

        assertThat(set.rules().get(2).action()).isEqualTo(Reject.with(CommandStatus.ESME_RINVDSTADR));

        Rule silent = set.rules().get(3);
        assertThat(silent.name()).isEqualTo("silent");
        assertThat(silent.match().account().pattern()).isEqualTo("quiet");
        assertThat(silent.action()).isEqualTo(Accept.silent());

        assertThat(set.defaultAction()).isEqualTo(Accept.receipt(DeliveryReceipt.State.DELIVERED,
                Duration.ofMillis(500)));
    }

    @Test
    void acceptsVariedSpellings() {
        RuleSet set = RulesYaml.parse("""
                rules:
                  - reject: 0x14
                  - reject: 20
                  - reject: RMSGQFUL
                  - reject: { status: esme_rthrottled }
                  - accept: { dlr: none }
                  - accept: { dlr: [DELIVRD, UNDELIV], after: 1s-3s }
                  - accept: { dlr: [ { delivrd: 3 }, { expired: 1, after: 250 } ] }
                """);

        assertThat(set.rules().get(0).action()).isEqualTo(Reject.with(CommandStatus.ESME_RMSGQFUL));
        assertThat(set.rules().get(1).action()).isEqualTo(Reject.with(CommandStatus.ESME_RMSGQFUL));
        assertThat(set.rules().get(2).action()).isEqualTo(Reject.with(CommandStatus.ESME_RMSGQFUL));
        assertThat(set.rules().get(3).action()).isEqualTo(Reject.with(CommandStatus.ESME_RTHROTTLED));
        assertThat(set.rules().get(4).action()).isEqualTo(Accept.silent());
        Accept range = (Accept) set.rules().get(5).action();
        assertThat(range.outcomes()).extracting(Accept.Outcome::delay)
                .containsOnly(new Delay(Duration.ofSeconds(1), Duration.ofSeconds(3)));
        Accept weighted = (Accept) set.rules().get(6).action();
        assertThat(weighted.outcomes()).extracting(Accept.Outcome::weight).containsExactly(3.0, 1.0);
        assertThat(weighted.outcomes().get(1).delay()).isEqualTo(Delay.fixed(Duration.ofMillis(250)));
        assertThat(set.rules()).allMatch(r -> r.match().isAny());
        assertThat(set.defaultAction()).isEqualTo(RuleSet.BUILT_IN_DEFAULT);
    }

    @Test
    void parsesGateActions() {
        RuleSet set = RulesYaml.parse("""
                rules:
                  - match: { to: "^88015" }
                    throttle: { tps: 5, then: ESME_RTHROTTLED }
                  - throttle: 3
                  - throttle: { tps: 2, then: 0x14 }
                  - latency: 2s
                  - latency: { delay: 1s-3s }
                  - latency: { after: 250ms }
                  - match: { account: chaos }
                    disconnect: { after: 10 }
                  - disconnect:
                  - disconnect: 2
                """);

        assertThat(set.rules()).extracting(Rule::action).containsExactly(
                Throttle.of(5), Throttle.of(3), new Throttle(2, CommandStatus.ESME_RMSGQFUL.code()),
                new Latency(Delay.fixed(Duration.ofSeconds(2))),
                new Latency(new Delay(Duration.ofSeconds(1), Duration.ofSeconds(3))),
                new Latency(Delay.fixed(Duration.ofMillis(250))),
                new Disconnect(10), new Disconnect(1), new Disconnect(2));
        String yaml = RulesYaml.format(set);
        assertThat(yaml).contains("throttle: { tps: 5, then: ESME_RTHROTTLED }").contains("latency: 1s-3s")
                .contains("disconnect: { after: 10 }");
        assertThat(RulesYaml.parse(yaml)).isEqualTo(set);
    }

    @Test
    void emptyDocumentsMeanTheBuiltInDefault() {
        assertThat(RulesYaml.parse("")).isEqualTo(RuleSet.DEFAULT);
        assertThat(RulesYaml.parse("rules:")).isEqualTo(RuleSet.DEFAULT);
        assertThat(RulesYaml.parse("rules: []")).isEqualTo(RuleSet.DEFAULT);
    }

    @Test
    void explainsMistakes() {
        assertThatThrownBy(() -> RulesYaml.parse("rules:\n  - match: { to: '(' }\n    accept:"))
                .isInstanceOf(RulesException.class).hasMessageContaining("rule 1").hasMessageContaining("regex");
        assertThatThrownBy(() -> RulesYaml.parse("rules:\n  - match: { dest: x }\n    accept:"))
                .hasMessageContaining("unknown match field 'dest'");
        assertThatThrownBy(() -> RulesYaml.parse("rules:\n  - accept:\n    reject: 1"))
                .hasMessageContaining("several actions");
        assertThatThrownBy(() -> RulesYaml.parse("rules:\n  - throttle: { tps: 0 }"))
                .hasMessageContaining("tps must be at least 1");
        assertThatThrownBy(() -> RulesYaml.parse("rules:\n  - throttle: { speed: 5 }"))
                .hasMessageContaining("unknown throttle option 'speed'");
        assertThatThrownBy(() -> RulesYaml.parse("rules:\n  - latency: 0s"))
                .hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> RulesYaml.parse("rules:\n  - latency:"))
                .hasMessageContaining("'latency' needs a delay");
        assertThatThrownBy(() -> RulesYaml.parse("rules:\n  - disconnect: { after: 0 }"))
                .hasMessageContaining("at least 1");
        assertThatThrownBy(() -> RulesYaml.parse("rules:\n  - default:\n      latency: 1s"))
                .hasMessageContaining("default must accept or reject");
        assertThatThrownBy(() -> RulesYaml.parse("rules:\n  - explode: now"))
                .hasMessageContaining("unknown action 'explode'");
        assertThatThrownBy(() -> RulesYaml.parse("rules:\n  - accept: { dlr: MAYBE }"))
                .hasMessageContaining("unknown DLR state 'MAYBE'");
        assertThatThrownBy(() -> RulesYaml.parse("rules:\n  - accept: { after: soon }"))
                .hasMessageContaining("not a delay");
        assertThatThrownBy(() -> RulesYaml.parse("rules:\n  - accept: { dlr: [ { DELIVRD: -5 } ] }"))
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> RulesYaml.parse("rules:\n  - reject: ESME_ROK"))
                .hasMessageContaining("must not be ESME_ROK");
        assertThatThrownBy(() -> RulesYaml.parse("rules:\n  - reject: WHATEVER"))
                .hasMessageContaining("unknown status 'WHATEVER'");
        assertThatThrownBy(() -> RulesYaml.parse("rules:\n  - default:\n      accept:\n  - default:\n      accept:"))
                .hasMessageContaining("only one default");
        assertThatThrownBy(() -> RulesYaml.parse("just a string")).hasMessageContaining("top-level 'rules:'");
        assertThatThrownBy(() -> RulesYaml.parse("rules: [ {accept: {dlr: [ ]} } ]")).hasMessageContaining("dlr");
        assertThatThrownBy(() -> RulesYaml.parse("rules:\n  - accept: { speed: fast }"))
                .hasMessageContaining("unknown accept option 'speed'");
        assertThatThrownBy(() -> RulesYaml.parse("rules: [\n")).hasMessageContaining("not valid YAML");
    }

    @Test
    void formatsWhatItParses() {
        RuleSet set = RulesYaml.parse(README_EXAMPLE);

        String yaml = RulesYaml.format(set);

        assertThat(yaml).contains("match: { to: \"^88017\" }").contains("accept: { dlr: DELIVRD, after: 2s }")
                .contains("- { DELIVRD: 80%, after: 3s }").contains("reject: ESME_RINVDSTADR")
                .contains("name: \"silent\"").contains("accept: { dlr: none }")
                .contains("default:\n      accept: { dlr: DELIVRD, after: 500ms }");
        assertThat(RulesYaml.parse(yaml)).isEqualTo(set);
        assertThat(RulesYaml.parse(RulesYaml.format(RuleSet.DEFAULT))).isEqualTo(RuleSet.DEFAULT);
    }

    @Test
    void parsesDelays() {
        assertThat(Delay.parse("2s")).isEqualTo(Delay.fixed(Duration.ofSeconds(2)));
        assertThat(Delay.parse("500ms")).isEqualTo(Delay.fixed(Duration.ofMillis(500)));
        assertThat(Delay.parse("1.5s")).isEqualTo(Delay.fixed(Duration.ofMillis(1500)));
        assertThat(Delay.parse("2m")).isEqualTo(Delay.fixed(Duration.ofMinutes(2)));
        assertThat(Delay.parse("1h")).isEqualTo(Delay.fixed(Duration.ofHours(1)));
        assertThat(Delay.parse("750")).isEqualTo(Delay.fixed(Duration.ofMillis(750)));
        assertThat(Delay.parse("1s-5s")).isEqualTo(new Delay(Duration.ofSeconds(1), Duration.ofSeconds(5)));
        assertThat(Delay.parse("1s .. 5s")).isEqualTo(new Delay(Duration.ofSeconds(1), Duration.ofSeconds(5)));
        assertThat(Delay.parse("500ms-2s").toString()).isEqualTo("500ms-2s");
        assertThat(Delay.parse("90s").toString()).isEqualTo("90s");
        assertThatThrownBy(() -> Delay.parse("5s-1s")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Delay.parse("soon")).isInstanceOf(IllegalArgumentException.class);
    }
}
