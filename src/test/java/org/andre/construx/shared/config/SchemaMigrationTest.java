package org.andre.construx.shared.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/** Boots against a schema built only by the Flyway migrations and lets Hibernate validate the entities. */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:migration-check;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class SchemaMigrationTest {

    @Autowired ApplicationContext context;

    @Test
    void migrationsMatchTheEntities() {
        assertNotNull(context);
    }
}
