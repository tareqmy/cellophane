package io.cellophane.server.config;

import io.cellophane.server.account.Account;
import io.cellophane.server.account.AccountRegistry;
import io.cellophane.server.api.MessageEvents;
import io.cellophane.server.message.Inbox;
import io.cellophane.server.message.MessageStore;
import io.cellophane.server.smpp.SessionRegistry;
import io.cellophane.server.smpp.SmppServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires the plain-Java core (accounts, inbox, SMPP listener) into the Spring context. */
@Configuration(proxyBeanMethods = false)
class CellophaneConfiguration {

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
    SmppServer smppServer(CellophaneProperties properties, AccountRegistry accounts, SessionRegistry sessions,
                          Inbox inbox) {
        return new SmppServer(properties.smppPort(), properties.systemId(), accounts, sessions, inbox);
    }
}
