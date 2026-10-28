package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.pricing.service.CsvParser.CsvRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvParserTest {

    @Test
    void splitsPlainFieldsAndKeepsEmptyOnes() {
        List<CsvRecord> records = CsvParser.parse("a,b,c\n1,,3\n");
        assertEquals(2, records.size());
        assertEquals(List.of("a", "b", "c"), records.get(0).fields());
        assertEquals(List.of("1", "", "3"), records.get(1).fields());
        assertEquals(List.of("x", ""), CsvParser.parse("x,").get(0).fields());
    }

    @Test
    void quotedFieldsMayHoldCommasEscapedQuotesAndLineBreaks() {
        List<CsvRecord> records = CsvParser.parse("\"Cemento, gris\",\"5\"\" tubo\",\"two\nlines\"\nnext,row");
        assertEquals(List.of("Cemento, gris", "5\" tubo", "two\nlines"), records.get(0).fields());
        assertEquals(List.of("next", "row"), records.get(1).fields());
        assertEquals(List.of(""), CsvParser.parse("\"\"").get(0).fields());
    }

    @Test
    void acceptsCrlfAndLfAndReportsThePhysicalLineWhereEachRecordStarts() {
        List<CsvRecord> records = CsvParser.parse("h1,h2\r\n\"a\r\nb\",c\r\nd,e\nf,g");
        assertEquals(4, records.size());
        assertEquals(1, records.get(0).line());
        assertEquals(5, records.get(3).line());
        assertEquals(List.of("a\r\nb", "c"), records.get(1).fields());
        assertEquals(2, records.get(1).line());
        assertEquals(4, records.get(2).line());
        assertEquals(List.of("d", "e"), records.get(2).fields());
        assertEquals(List.of("f", "g"), CsvParser.parse("h\nf,g").get(1).fields());
    }

    @Test
    void stripsALeadingByteOrderMark() {
        assertEquals(List.of("material", "unit"), CsvParser.parse("﻿material,unit\n").get(0).fields());
    }

    @Test
    void skipsBlankLinesButKeepsCountingThem() {
        List<CsvRecord> records = CsvParser.parse("h\n\na\r\n\r\nb\n\n");
        assertEquals(3, records.size());
        assertEquals(3, records.get(1).line());
        assertEquals(5, records.get(2).line());
        assertTrue(CsvParser.parse("").isEmpty());
    }

    @Test
    void rejectsAnUnterminatedQuoteOrTextAfterAClosingQuote() {
        CsvParser.CsvFormatException open = assertThrows(CsvParser.CsvFormatException.class,
                () -> CsvParser.parse("h\n\"open,field\n"));
        assertEquals(2, open.getLine());
        CsvParser.CsvFormatException trailing = assertThrows(CsvParser.CsvFormatException.class,
                () -> CsvParser.parse("\"a\"b,c"));
        assertEquals(1, trailing.getLine());
    }
}
