package org.andre.construx.modules.report;

import org.andre.construx.modules.attendance.AttendanceTestBase;
import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.support.Api.Session;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The "Asistencia" section of the Site Report PDF, read back as text from a report generated through the API:
 * the Attendance of the Site over its life, built from its Check-ins.
 */
class SiteReportAttendanceTest extends AttendanceTestBase {
    private static final String DAY_10 = MORNING;
    private static final String DAY_11 = "2026-03-11T15:00:00Z";
    private static final String DAY_12 = "2026-03-12T15:00:00Z";

    @Autowired JdbcTemplate jdbc;

    @Test
    void printsDaysAverageAndEachPersonByDaysPresent() throws Exception {
        profiles.save(new UserProfile(owner.user(), "Zoe Zamora"));
        checkInOn(DAY_10, owner, worker, otherWorker);
        checkInOn(DAY_11, owner, worker);
        checkInOn(DAY_12, owner);

        String section = attendanceSection(generateReport());

        assertThat(section).contains("Días con registros de entrada: 3");
        // 6 person-days over 3 days
        assertThat(section).contains("Promedio de personas por día: 2.0");
        assertThat(section).contains("Persona", "Días presentes", "Último día presente");
        String noProfile = otherWorker.user().getEmail();
        assertThat(section).containsSubsequence(
                "Zoe Zamora", "3", "12/03/2026",
                "Ana Perez", "2", "11/03/2026",
                noProfile, "1", "10/03/2026");
    }

    @Test
    void sectionComesAfterTheTeam() throws Exception {
        checkInOn(DAY_10, worker);

        String report = generateReport();

        assertThat(report.indexOf("Equipo")).isNotNegative();
        assertThat(report.indexOf("Asistencia")).isGreaterThan(report.indexOf("Equipo"));
    }

    @Test
    void siteWithoutCheckInsSaysSoAndPrintsNoTable() throws Exception {
        String section = attendanceSection(generateReport());

        assertThat(section).contains("Sin registros de entrada.");
        assertThat(section).doesNotContain("Días presentes", "Promedio");
    }

    @Test
    void twoCheckInsOfOnePersonOnOneDayCountAsOneDay() throws Exception {
        checkInOn(DAY_10, worker);
        insertCheckIn(workerMembership, "2026-03-10", "2026-03-10T20:00:00Z");
        checkInOn(DAY_11, otherWorker);

        String section = attendanceSection(generateReport());

        assertThat(section).contains("Días con registros de entrada: 2");
        assertThat(section).contains("Promedio de personas por día: 1.0");
        assertThat(section).containsSubsequence("Ana Perez", "1", "10/03/2026");
    }

    @Test
    void removedCheckInIsNotCounted() throws Exception {
        checkInOn(DAY_10, worker, otherWorker);
        checkInOn(DAY_11, worker);
        String removed = read(checkInOn(DAY_12, worker), "$.id");
        remove(removed);
        remove(read(checkInOn(DAY_12, otherWorker), "$.id"));

        String section = attendanceSection(generateReport());

        assertThat(section).contains("Días con registros de entrada: 2");
        assertThat(section).contains("Promedio de personas por día: 1.5");
        assertThat(section).containsSubsequence("Ana Perez", "2", "11/03/2026");
        assertThat(section).doesNotContain("12/03/2026");
    }

    @Test
    void personWhoLeftTheCompanyStillAppearsByName() throws Exception {
        checkInOn(DAY_10, worker);
        api.delete(owner, org + "/members/" + workerMembership).andExpect(status().isNoContent());

        String section = attendanceSection(generateReport());

        assertThat(section).containsSubsequence("Ana Perez", "1", "10/03/2026");
    }

    @Test
    void checkInsOfAnotherSiteAreNotCounted() throws Exception {
        UUID other = createSite("Annex");
        api.post(owner, sitePath(other) + "/attendance/check-ins", "{}").andExpect(status().isCreated());

        assertThat(attendanceSection(generateReport())).contains("Sin registros de entrada.");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Each session checks itself in at that moment; returns the last response. */
    private ResultActions checkInOn(String moment, Session... sessions) throws Exception {
        clock.set(moment);
        ResultActions last = null;
        for (Session session : sessions) {
            last = checkIn(session).andExpect(status().isCreated());
        }
        return last;
    }

    /** A second row for a person and day, which the API never stores on a repeat but two racing requests can. */
    private void insertCheckIn(UUID membershipId, String day, String moment) {
        jdbc.update("insert into check_in (id, project_id, membership_id, check_in_day, checked_in_at, "
                        + "recorded_by_membership_id) values (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), siteId, membershipId, java.time.LocalDate.parse(day),
                java.time.OffsetDateTime.parse(moment), membershipId);
    }

    /**
     * Marks a Check-in removed in the table; there is no removal endpoint on this branch yet (#59). Only
     * {@code removed_at} is set: the test schema is built from the entities, which do not map who removed it.
     */
    private void remove(String checkInId) {
        jdbc.update("update check_in set removed_at = ? where id = ?",
                java.time.OffsetDateTime.parse("2026-03-12T16:00:00Z"), UUID.fromString(checkInId));
    }

    /** Generates a report as the owner and returns the text of its PDF. */
    private String generateReport() throws Exception {
        clock.set("2026-03-13T15:00:00Z");
        ResultActions created = api.post(owner, site + "/reports", "{\"title\":\"Report\"}")
                .andExpect(status().isCreated());
        byte[] pdf = Base64.getDecoder().decode(read(created, "$.pdfBase64"));
        try (PDDocument document = Loader.loadPDF(pdf)) {
            // In reading order: the PDF stores a page's paragraphs apart from its tables.
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    private static String attendanceSection(String report) {
        int start = report.indexOf("Asistencia");
        assertThat(start).as("the report has an Asistencia section").isNotNegative();
        return report.substring(start);
    }
}
