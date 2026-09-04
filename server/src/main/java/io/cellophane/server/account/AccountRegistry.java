package io.cellophane.server.account;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The configured ESME accounts, keyed by system_id. */
public final class AccountRegistry {

    private final Map<String, Account> accounts = new LinkedHashMap<>();

    public AccountRegistry(Collection<Account> accounts) {
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("at least one account is required");
        }
        for (Account account : accounts) {
            if (this.accounts.putIfAbsent(account.systemId(), account) != null) {
                throw new IllegalArgumentException("duplicate account system_id '" + account.systemId() + "'");
            }
        }
    }

    public Optional<Account> find(String systemId) {
        return Optional.ofNullable(accounts.get(systemId));
    }

    /** The account if the credentials match; empty for an unknown system_id or a wrong password. */
    public Optional<Account> authenticate(String systemId, String password) {
        return find(systemId).filter(a -> a.password().equals(password));
    }

    public List<Account> all() {
        return List.copyOf(accounts.values());
    }
}
