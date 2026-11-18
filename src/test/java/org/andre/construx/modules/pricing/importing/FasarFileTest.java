package org.andre.construx.modules.pricing.importing;

import org.andre.construx.modules.pricing.model.FasarCalculator;
import org.andre.construx.modules.pricing.model.FasarConcept;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reading the PU Win+ FASAR workbook (docs/pricing.md §4.5, §5.2) from a synthetic HSSF/XSSF workbook. */
class FasarFileTest {
    @TempDir Path dir;

    private FasarFile read(SyntheticFasar wb) {
        return FasarFile.read(wb.file(dir).toFile());
    }

    private static ImportFailedException failure(Runnable reading) {
        try {
            reading.run();
        } catch (ImportFailedException ex) {
            return ex;
        }
        throw new AssertionError("expected the FASAR workbook to fail");
    }

    @Test
    void readsTheParametersAndTheSalaryTableOfAnXlsWorkbook() {
        SyntheticFasar wb = new SyntheticFasar();
        wb.year = 2024;
        FasarFile file = read(wb);

        assertThat(file.year()).isEqualTo(2024);
        assertThat(file.umaCap()).isEqualByComparingTo("100");
        assertThat(file.minimumWage()).isEqualByComparingTo("200");
        FasarCalculator.Parameters p = file.parameters();
        assertThat(p.uma()).isEqualByComparingTo("100");
        assertThat(p.sumEmployerAndWorker()).isTrue();
        assertThat(p.sbcCapUma()).isEqualByComparingTo("25");
        assertThat(p.excessThresholdUma()).isEqualByComparingTo("3");
        assertThat(p.factorRounding()).isEqualTo(6);
        FasarCalculator.Days d = p.days();
        assertThat(d.calendar()).isEqualByComparingTo("365");
        assertThat(d.sundays()).isEqualByComparingTo("52");
        assertThat(d.vacation()).isEqualByComparingTo("6");
        assertThat(d.mondays()).isEqualByComparingTo("3");
        assertThat(d.holidays()).isEqualByComparingTo("7");
        assertThat(d.weather()).isEqualByComparingTo("3");
        assertThat(d.custom()).isEqualByComparingTo("4");
        assertThat(d.leaveSick()).isEqualByComparingTo("0");
        assertThat(d.union()).isEqualByComparingTo("0");
        assertThat(d.bonus()).isEqualByComparingTo("15");
        // 2.3 días equivalentes 1.0 at 25 %: four Sundays worked
        assertThat(d.workedSundays()).isEqualByComparingTo("4");
        assertThat(p.vacationPremiumPct()).isEqualByComparingTo("0.25");
        assertThat(p.sundayPremiumPct()).isEqualByComparingTo("0.25");
        assertThat(p.contributions()).hasSize(12);
        assertThat(p.contributions().get(FasarConcept.RISK).employer()).isEqualByComparingTo("0.05");
        assertThat(p.contributions().get(FasarConcept.UNEMPLOYMENT_OLD_AGE).worker()).isEqualByComparingTo("0.01125");
        assertThat(p.contributions().get(FasarConcept.PAYROLL_TAX).employer()).isEqualByComparingTo("0.03");
        assertThat(p.otherChargesPct()).isEqualByComparingTo("0");

        assertThat(file.salaries()).extracting(FasarFile.Salary::code).containsExactly("MO011", "MO020");
        FasarFile.Salary peon = file.salaries().get(0);
        assertThat(peon.row()).isEqualTo(7);
        assertThat(peon.nominalWage()).isEqualByComparingTo("350");
        assertThat(peon.fsr()).isEqualByComparingTo("1.7");
        assertThat(peon.realWage()).isEqualByComparingTo("595");
    }

    @Test
    void readsAnXlsxWorkbookAndOtherChargesFromTheirColumn() {
        SyntheticFasar wb = new SyntheticFasar();
        wb.xlsx = true;
        wb.employer[11] = 0.02;
        wb.sundayPremiumPct = 0.0;
        wb.sundayPremiumDays = 0.0;
        FasarFile file = read(wb);
        assertThat(file.parameters().otherChargesPct()).isEqualByComparingTo("0.02");
        assertThat(file.parameters().days().workedSundays()).isEqualByComparingTo("0");
    }

    @Test
    void aMissingSheetFails() {
        SyntheticFasar wb = new SyntheticFasar();
        wb.withoutSheets.add("b) CapturaDatos");
        ImportFailedException ex = failure(() -> read(wb));
        assertThat(ex.getCode()).isEqualTo("FASAR_MISSING_SHEET");
        assertThat(ex.getSheet()).isEqualTo("b) CapturaDatos");
    }

    @Test
    void missingBlocksFail() {
        SyntheticFasar noNames = new SyntheticFasar();
        noNames.withNames = false;
        ImportFailedException uma = failure(() -> read(noNames));
        assertThat(uma.getCode()).isEqualTo("FASAR_MISSING_BLOCK");
        assertThat(uma.getSheet()).isEqualTo("uma");

        SyntheticFasar noPatron = new SyntheticFasar();
        noPatron.withPatronRow = false;
        ImportFailedException factors = failure(() -> read(noPatron));
        assertThat(factors.getCode()).isEqualTo("FASAR_MISSING_BLOCK");
        assertThat(factors.getSheet()).isEqualTo("a)TablaFactores");
        assertThat(factors.getColumn()).isEqualTo("PATRON");

        SyntheticFasar noDay = new SyntheticFasar();
        noDay.withoutDayRows.add("1.3");
        ImportFailedException capture = failure(() -> read(noDay));
        assertThat(capture.getCode()).isEqualTo("FASAR_MISSING_BLOCK");
        assertThat(capture.getSheet()).isEqualTo("b) CapturaDatos");
        assertThat(capture.getColumn()).isEqualTo("1.3");

        SyntheticFasar noHeader = new SyntheticFasar();
        noHeader.withSalaryHeader = false;
        ImportFailedException salaries = failure(() -> read(noHeader));
        assertThat(salaries.getCode()).isEqualTo("FASAR_MISSING_BLOCK");
        assertThat(salaries.getSheet()).isEqualTo("d)Salarios");
        assertThat(salaries.getRow()).isEqualTo(6);
    }

    @Test
    void invalidNumbersFailWithTheirCell() {
        SyntheticFasar text = new SyntheticFasar();
        text.employer[3] = "siete";
        ImportFailedException rate = failure(() -> read(text));
        assertThat(rate.getCode()).isEqualTo("FASAR_INVALID_NUMBER");
        assertThat(rate.getSheet()).isEqualTo("a)TablaFactores");
        assertThat(rate.getRow()).isEqualTo(6);
        assertThat(rate.getColumn()).isEqualTo("F");

        SyntheticFasar negative = new SyntheticFasar();
        negative.days.put("1.4", -1);
        ImportFailedException day = failure(() -> read(negative));
        assertThat(day.getCode()).isEqualTo("FASAR_INVALID_NUMBER");
        assertThat(day.getSheet()).isEqualTo("b) CapturaDatos");
        assertThat(day.getRow()).isEqualTo(21);

        SyntheticFasar wage = new SyntheticFasar();
        wage.salaries.get(1)[2] = "quinientos";
        ImportFailedException salary = failure(() -> read(wage));
        assertThat(salary.getCode()).isEqualTo("FASAR_INVALID_NUMBER");
        assertThat(salary.getSheet()).isEqualTo("d)Salarios");
        assertThat(salary.getRow()).isEqualTo(8);

        SyntheticFasar noWorkingDays = new SyntheticFasar();
        noWorkingDays.days.put("1.1", 400);
        assertThat(failure(() -> read(noWorkingDays)).getCode()).isEqualTo("FASAR_INVALID_NUMBER");

        SyntheticFasar rateAboveOne = new SyntheticFasar();
        rateAboveOne.employer[0] = 7.5;
        assertThat(failure(() -> read(rateAboveOne)).getCode()).isEqualTo("FASAR_INVALID_NUMBER");
    }

    @Test
    void anUnreadableFileFails() throws Exception {
        Path junk = java.nio.file.Files.write(dir.resolve("junk.xls"), new byte[] {1, 2, 3});
        assertThatThrownBy(() -> FasarFile.read(junk.toFile())).isInstanceOf(ImportFailedException.class)
                .extracting(ex -> ((ImportFailedException) ex).getCode()).isEqualTo("WORKBOOK_UNREADABLE");
    }

    @Test
    void decimalsFromExcelDoublesAreKeptAsWritten() {
        SyntheticFasar wb = new SyntheticFasar();
        wb.employer[0] = 0.075887499999999997;
        FasarFile file = read(wb);
        assertThat(file.parameters().contributions().get(FasarConcept.RISK).employer())
                .isEqualByComparingTo(new BigDecimal("0.0758875"));
    }
}
