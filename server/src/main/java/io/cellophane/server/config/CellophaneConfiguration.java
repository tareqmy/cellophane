package io.cellophane.server.config;

import io.cellophane.server.account.Account;
import io.cellophane.server.account.AccountRegistry;
import io.cellophane.server.api.MessageEvents;
import io.cellophane.server.message.Inbox;
import io.cellophane.server.message.MessageStore;
import io.cellophane.server.operator.DelayedExecutor;
import io.cellophane.server.operator.Metrics;
import io.cellophane.server.operator.MoInjector;
import io.cellophane.server.operator.Operator;
import io.cellophane.server.operator.ReceiptDispatcher;
import io.cellophane.server.operator.ScheduledDelayedExecutor;
import io.cellophane.server.rules.RuleEngine;
import io.cellophane.server.rules.RuleSet;
import io.cellophane.server.rules.RulesException;
import io.cellophane.server.smpp.SessionRegistry;
import io.cellophane.server.smpp.SmppServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.SplittableRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wires the plain-Java core (accounts, inbox, SMPP listener) into the Spring context. */
@Configuration(proxyBeanMethods = false)
class CellophaneConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CellophaneConfiguration.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    AccountRegistry accountRegistry(CellophaneProperties properties) {
        return new AccountRegistry(Account.parseAll(properties.accounts()));
    }

    @Bean
    MessageStore messageStore(CellophaneProperties properties) {
        return new MessageStore(properties.maxMessages());
    }

    @Bean
    MessageEvents messageEvents() {
        return new MessageEvents();
    }

    @Bean
    Inbox inbox(MessageStore store, MessageEvents events, Clock clock) {
        return new Inbox(store, events, clock);
    }

    @Bean
    SessionRegistry sessionRegistry(Clock clock) {
        return new SessionRegistry(clock);
    }

    @Bean
    RuleEngine ruleEngine(CellophaneProperties properties) {
        if (properties.rules().isBlank()) {
            log.info("no CELLOPHANE_RULES file; using the built-in default (deliver after 500ms)");
            return new RuleEngine(RuleSet.DEFAULT);
        }
        Path path = Path.of(properties.rules());
        try {
            RuleEngine engine = RuleEngine.fromYaml(Files.readString(path));
            log.info("loaded {} rule(s) from {}", engine.current().rules().size(), path);
            return engine;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read rules file " + path, e);
        } catch (RulesException e) {
            throw new IllegalStateException("rules file " + path + ": " + e.getMessage(), e);
        }
    }

    @Bean
    DelayedExecutor delayedExecutor() {
        return new ScheduledDelayedExecutor();
    }

    @Bean
    Metrics metrics(Clock clock) {
        return new Metrics(clock);
    }

    @Bean
    ReceiptDispatcher receiptDispatcher(SessionRegistry sessions, Inbox inbox, Metrics metrics) {
        return new ReceiptDispatcher(sessions, inbox, metrics);
    }

    @Bean
    Operator operator(RuleEngine rules, Inbox inbox, ReceiptDispatcher receipts, DelayedExecutor timer, Clock clock,
                      Metrics metrics) {
        // SplittableRandom lives in java.base; RandomGenerator.getDefault() needs jdk.random, which the
        // buildpack's jlinked JRE leaves out, and the app then fails to start inside the container.
        return new Operator(rules, inbox, receipts, timer, clock, new SplittableRandom(), metrics);
    }

    @Bean
    MoInjector moInjector(SessionRegistry sessions, Metrics metrics) {
        return new MoInjector(sessions, metrics);
    }

    @Bean
    SmppServer smppServer(CellophaneProperties properties, AccountRegistry accounts, SessionRegistry sessions,
                          Operator operator, ReceiptDispatcher receipts) {
        return new SmppServer(properties.smppPort(), properties.idleTimeout(), properties.systemId(), accounts,
                sessions, operator, receipts);
    }
}
