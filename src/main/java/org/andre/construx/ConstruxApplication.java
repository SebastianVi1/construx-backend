package org.andre.construx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ConstruxApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConstruxApplication.class, args);
    }

}
