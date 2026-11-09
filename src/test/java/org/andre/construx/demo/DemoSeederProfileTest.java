package org.andre.construx.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Only the demo profile seeds; any other profile, production included, never does. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:demo-seed-off;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "construx.demo.password=Seed-Pass-123"})
@ActiveProfiles("test")
class DemoSeederProfileTest {
    @Autowired ApplicationContext context;

    @Test
    void noSeederWithoutTheDemoProfile() {
        assertThat(context.getBeansOfType(DemoSeeder.class)).isEmpty();
    }
}
