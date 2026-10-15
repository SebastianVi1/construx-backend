# Repository Guidelines

## Project Structure & Module Organization

This is a Java 21 Spring Boot application built with Maven. Application code lives in `src/main/java/org/andre/construx`. Domain entities are grouped under `modules/identity`, `modules/organization`, and `modules/project`; cross-cutting configuration, validation, and error handling live under `shared`. Runtime settings are in `src/main/resources/application.properties`. Tests mirror the Java package layout in `src/test/java`. `src/main/resources/static` and `templates` are available for assets and views but currently contain no application files.

## Build, Test, and Development Commands

- `./mvnw compile` compiles the application.
- `./mvnw test` runs the JUnit test suite.
- `./mvnw -Dtest=GlobalExceptionHandlerTest test` runs the isolated MVC error-handler tests.
- `./mvnw package` builds the application JAR in `target/`.
- `./mvnw spring-boot:run` starts the application with the configured PostgreSQL database and OAuth client settings.

Use `mvnw.cmd` instead of `./mvnw` on Windows.

## Coding Style & Naming Conventions

Follow the existing four-space Java indentation and package naming. Use `PascalCase` for classes and enums, `camelCase` for methods and fields, and descriptive domain names such as `OrganizationMembership` or `ProjectAccess`. Keep entity relationships and invariants inside the relevant model classes; use `shared` for code used across modules. The project uses Lombok for selected boilerplate. No formatter or lint plugin is configured, so match nearby code when editing.

## Testing Guidelines

Tests use JUnit Jupiter and Spring's test support; MVC handler tests use standalone `MockMvc`. Name test classes `*Test` or `*Tests` and give test methods behavior-focused names. Add focused tests for changed behavior. `ConstruxApplicationTests` loads the full Spring context: its configured JPA `ddl-auto=update` may change the connected PostgreSQL schema. Run it against a disposable database.

## Commit & Pull Request Guidelines

Recent commits use short, imperative, lowercase subjects such as `add Activity and Goal models with validation`. Keep commits focused. In pull requests, describe the behavior changed, affected domain relationships, and tests run; link the relevant issue when one exists. Include screenshots only for visible UI changes.

## Security & Configuration

Do not commit local credentials or `.env` files. Supply OAuth client values through environment variables. Check database settings before running the app or full-context tests; `application.properties` currently targets a local PostgreSQL instance.

#Architecture 

This project uses a layered architecture with domain-driven design principles. The core domain logic resides in the `modules` packages, while shared utilities and configurations are placed in the `shared` package. Controllers handle HTTP requests and delegate to service classes, which encapsulate business logic. Repositories manage data access, leveraging Spring Data JPA for ORM capabilities.
For security, the app uses Spring Security implementing the documentation provided by Spring Boot. Authentication is handled via OAuth2 and Username and Password, and authorization is role-based, ensuring that users can only access resources they are permitted to.


