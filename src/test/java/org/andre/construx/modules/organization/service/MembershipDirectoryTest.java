package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.support.Api;
import org.andre.construx.support.Api.Session;
import org.andre.construx.support.Members;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Other modules read memberships through the directory; it never hands out another tenant's members. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MembershipDirectoryTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAccessRepository accesses;
    @Autowired PlatformTransactionManager txm;
    @Autowired MembershipDirectory directory;

    @Test
    void byIdsReturnsOnlyMembershipsOfTheGivenOrganization() throws Exception {
        Api api = new Api(mvc, users, tokens);
        Members members = new Members(txm, organizations, memberships, projects, accesses);
        Session acmeOwner = api.signUp();
        UUID acme = api.createOrganization(acmeOwner, "Acme " + UUID.randomUUID());
        Session otherOwner = api.signUp();
        UUID other = api.createOrganization(otherOwner, "Other " + UUID.randomUUID());
        UUID acmeWorker = members.add(acme, api.signUp().user(), "WORKER");
        UUID foreignWorker = members.add(other, api.signUp().user(), "WORKER");

        List<UUID> found = directory.byIds(acme, List.of(acmeWorker, foreignWorker, UUID.randomUUID())).stream()
                .map(OrganizationMembership::getId).toList();

        assertEquals(List.of(acmeWorker), found);
    }
}
