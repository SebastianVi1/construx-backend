package org.andre.construx.shared.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.andre.construx.modules.identity.model.UserAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {
    private MockMvc mvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new ErrorController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void invalidDomainInputReturnsBadRequest() throws Exception {
        mvc.perform(get("/errors/argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.detail").value("Email is required"));
    }

    @Test
    void invalidStateReturnsConflict() throws Exception {
        mvc.perform(get("/errors/state"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.detail").value("The operation conflicts with the current state."));
    }

    @Test
    void missingResourceReturnsNotFound() throws Exception {
        mvc.perform(get("/errors/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void databaseConstraintReturnsSafeConflict() throws Exception {
        mvc.perform(get("/errors/database"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"))
                .andExpect(jsonPath("$.detail").value("The operation conflicts with existing data."));
    }

    @Test
    void forbiddenOperationReturnsForbidden() throws Exception {
        mvc.perform(get("/errors/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void codedForbiddenRuleReturnsForbiddenWithItsCode() throws Exception {
        mvc.perform(get("/errors/forbidden-rule"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_ABOVE_CALLER"))
                .andExpect(jsonPath("$.detail").value("That role holds permissions you do not have"));
    }

    @Test
    void ruleViolationPropertiesAreAddedToTheProblem() throws Exception {
        mvc.perform(get("/errors/rule-with-properties"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_DEPENDENCY_MISSING"))
                .andExpect(jsonPath("$.action").value("MANAGE_PLAN"))
                .andExpect(jsonPath("$.requires").value("VIEW_PLAN"));
    }

    @Test
    void unauthenticatedOperationReturnsUnauthorized() throws Exception {
        mvc.perform(get("/errors/unauthenticated"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void unexpectedFailureHidesInternalMessage() throws Exception {
        mvc.perform(get("/errors/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
    }

    @Test
    void invalidBodyIncludesFieldErrors() throws Exception {
        mvc.perform(post("/errors/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.name[0]").value("must not be blank"));
    }

    @Test
    void malformedBodyReturnsBadRequest() throws Exception {
        mvc.perform(post("/errors/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_BODY"));
    }

    @RestController
    static class ErrorController {
        @GetMapping("/errors/argument")
        void invalidArgument() {
            new UserAccount(null, "hashed-password");
        }

        @GetMapping("/errors/state")
        void invalidState() {
            throw new IllegalStateException("Invitation is not active");
        }

        @GetMapping("/errors/missing")
        void missingResource() {
            throw new ResourceNotFoundException("Project not found");
        }

        @GetMapping("/errors/database")
        void dataConflict() {
            throw new DataIntegrityViolationException("Internal constraint name");
        }

        @GetMapping("/errors/forbidden")
        void forbidden() {
            throw new AccessDeniedException("Not allowed");
        }

        @GetMapping("/errors/forbidden-rule")
        void forbiddenRule() {
            throw new ForbiddenRuleException("ROLE_ABOVE_CALLER", "That role holds permissions you do not have");
        }

        @GetMapping("/errors/rule-with-properties")
        void ruleWithProperties() {
            throw new RuleViolationException("ROLE_DEPENDENCY_MISSING", "MANAGE_PLAN requires VIEW_PLAN",
                    java.util.Map.of("action", "MANAGE_PLAN", "requires", "VIEW_PLAN"));
        }

        @GetMapping("/errors/unauthenticated")
        void unauthenticated() {
            throw new BadCredentialsException("Sensitive credential detail");
        }

        @GetMapping("/errors/unexpected")
        void unexpected() {
            throw new RuntimeException("Sensitive internal message");
        }

        @PostMapping("/errors/validation")
        void validate(@Valid @RequestBody Input input) {
        }
    }

    record Input(@NotBlank String name) {
    }
}
