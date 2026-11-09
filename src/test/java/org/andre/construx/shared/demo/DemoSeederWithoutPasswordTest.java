package org.andre.construx.shared.demo;

import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Without a configured demo password the demo profile seeds nothing, so no account has a guessable password. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:demo-seed-no-password;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false"})
@ActiveProfiles({"test", "demo"})
class DemoSeederWithoutPasswordTest {
    @Autowired UserAccountRepository users;

    @Test
    void createsNoAccounts() {
        assertThat(users.count()).isZero();
    }
}
