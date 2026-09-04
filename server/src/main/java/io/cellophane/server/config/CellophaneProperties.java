package io.cellophane.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under the {@code cellophane.*} prefix. Each maps to a {@code CELLOPHANE_*} environment variable
 * through Spring's relaxed binding, e.g. {@code CELLOPHANE_SMPP_PORT}.
 *
 * @param smppPort    SMPP listen port; 0 picks a free port
 * @param accounts    comma-separated {@code system_id:password[:window]} ESME accounts
 * @param maxMessages in-memory inbox capacity; the oldest message is dropped when full
 * @param systemId    the system_id the fake operator reports in bind responses
 */
@ConfigurationProperties("cellophane")
public record CellophaneProperties(
        @DefaultValue("2775") int smppPort,
        @DefaultValue("cellophane:cellophane") String accounts,
        @DefaultValue("10000") int maxMessages,
        @DefaultValue("cellophane") String systemId) {
}
