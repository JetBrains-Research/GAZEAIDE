package io.github.gazehighlighter;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodeRegionTest {
    @Test void findsNestedBranchWithoutCountingBracesInStringsOrComments() {
        String code = "class Demo {\n void test() {\n  if (ok) {\n   log(\"}\"); // }\n   run();\n  }\n }\n}";
        CodeRegion r = CodeRegion.find(code, 4);
        assertEquals(2, r.firstLine()); assertEquals(5, r.lastLine());
        assertTrue(r.text().contains("run();"));
    }
    @Test void findsPythonBodyAndExcludesFollowingSibling() {
        CodeRegion r = CodeRegion.find("def a(x):\n    value = x + 1\n    return value\n\ndef b():\n    pass", 2);
        assertEquals(0, r.firstLine()); assertEquals(3, r.lastLine()); assertFalse(r.text().contains("def b"));
    }
    @Test void preservesOffsetsWhenMaskingEscapesAndComments() {
        String text = "foo = \"a\\\"{\"; /* }\n { */ foo // foo\nfoo";
        String masked = CodeRegion.mask(text);
        assertEquals(text.length(), masked.length());
        assertEquals(text.chars().filter(c -> c == '\n').count(), masked.chars().filter(c -> c == '\n').count());
        assertEquals(3, CodeRegion.occurrences(text, "foo").size());
    }
    @Test void excludesNamesInStringsCommentsAndLongerIdentifiers() {
        assertEquals(2, CodeRegion.occurrences("total + subtotal + total; // total\n\"total\"", "total").size());
    }
    @Test void supportsUnicodeNamesAndRejectsKeywordsAndNumbers() {
        assertEquals("résultat", CodeRegion.identifier("résultat = 5", 3));
        assertNull(CodeRegion.identifier("return value", 2));
        assertNull(CodeRegion.identifier("12345", 2));
        assertNull(CodeRegion.identifier("\"value\"", 3));
    }
    @Test void fallbackRegionsStayStableAcrossAdjacentLines() {
        String text = String.join("\n", java.util.Collections.nCopies(20, "plain text"));
        assertEquals(CodeRegion.find(text, 1), CodeRegion.find(text, 6));
        assertNotEquals(CodeRegion.find(text, 6).firstLine(), CodeRegion.find(text, 9).firstLine());
    }
    @Test void handlesEmptyFileUnmatchedBracesAndOutOfBoundsTargets() {
        assertEquals(0, CodeRegion.find("", 999).firstLine());
        assertDoesNotThrow(() -> CodeRegion.find("void foo() {\n  return;", -3));
    }
    @Test void handlesPythonMultilineStringsContainingBracesAndDefinitions() {
        String code = "def real():\n    text = \"\"\"\n{\ndef fake():\n}\n\"\"\"\n    return text\ndef sibling():\n    pass";
        assertEquals(0, CodeRegion.find(code, 6).firstLine());
    }
}
