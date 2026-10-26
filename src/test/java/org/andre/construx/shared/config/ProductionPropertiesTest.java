package org.andre.construx.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ProductionPropertiesTest {

    private static final Properties PROPS = new Properties();

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = new ClassPathResource("application.properties").getInputStream()) {
            PROPS.load(in);
        }
    }

    @Test
    void schemaIsOwnedByFlywayNotHibernate() {
        assertThat(PROPS.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void sqlIsNotLoggedByDefault() {
        assertThat(PROPS.getProperty("spring.jpa.show-sql", "false")).isEqualTo("false");
    }
}
