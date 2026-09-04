package io.cellophane.server.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class AccountTest {

    @Test
    void parsesSpecsWithAndWithoutWindow() {
        assertThat(Account.parseAll("app:secret:100, chaos:chaos ,,")).containsExactly(
                new Account("app", "secret", 100),
                new Account("chaos", "chaos", Account.DEFAULT_WINDOW_SIZE));
    }

    @Test
    void rejectsMalformedSpecs() {
        assertThatThrownBy(() -> Account.parse("nopassword")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Account.parse("a:b:c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Account.parse(":b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountRegistry(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountRegistry(List.of(Account.parse("a:b"), Account.parse("a:c"))))
                .hasMessageContaining("duplicate");
    }

    @Test
    void authenticatesOnlyMatchingCredentials() {
        AccountRegistry registry = new AccountRegistry(Account.parseAll("app:secret"));

        assertThat(registry.authenticate("app", "secret")).isPresent();
        assertThat(registry.authenticate("app", "wrong")).isEmpty();
        assertThat(registry.authenticate("nobody", "secret")).isEmpty();
        assertThat(registry.find("app")).isPresent();
    }
}
