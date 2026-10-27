package org.andre.construx.modules.identity.service;

import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises additive Hibernate schema update against a database with an existing account. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:profile-upgrade;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=update"
})
@ActiveProfiles("test")
@ContextConfiguration(initializers = UserProfileUpgradeTest.ExistingAccount.class)
class UserProfileUpgradeTest {
    private static final UUID EXISTING_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired UserAccountRepository users;
    @Autowired UserProfileRepository profiles;
    @Autowired UserProfileService service;

    @Test
    void existingAccountSurvivesOptionalProfileTableAddition() {
        var account = users.findById(EXISTING_ID).orElseThrow();
        assertEquals("legacy@example.test", account.getEmail());
        assertTrue(profiles.findById(EXISTING_ID).isEmpty());
        assertNull(service.get(EXISTING_ID).fullName());
        service.update(EXISTING_ID, "Legacy User", "+14155552671");
        assertEquals("Legacy User", profiles.findById(EXISTING_ID).orElseThrow().getFullName());
        assertEquals("legacy@example.test", users.findById(EXISTING_ID).orElseThrow().getEmail());
    }

    static class ExistingAccount implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            try (var connection = DriverManager.getConnection(
                    "jdbc:h2:mem:profile-upgrade;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
                    var statement = connection.createStatement()) {
                statement.execute("create table \"user-account\" (id uuid primary key, "
                        + "email varchar(254) not null unique, password varchar(255), "
                        + "email_verified boolean not null, status enum ('ACTIVE','DISABLED') not null)");
                // Flyway baselines at V1, so later migrations alter tables a real V1 database already has.
                statement.execute("create table organization_role (id uuid primary key)");
                statement.execute("create table organization_invitation (id uuid primary key)");
                statement.execute("create table project (id uuid primary key)");
                statement.execute("insert into \"user-account\" "
                        + "(id,email,password,email_verified,status) values "
                        + "('" + EXISTING_ID + "','legacy@example.test','hash',true,'ACTIVE')");
            } catch (Exception ex) {
                throw new IllegalStateException("Could not prepare existing account schema", ex);
            }
        }
    }
}
