// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.rowsail.callignore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Off-device checks for pattern parsing and matching. */
public class RulesTest {
    private static final Rules RULES = Rules.parse(String.join("\n",
            "4045551234 allow   # exception first",
            "888*",
            "1877*  voicemail",
            "(404) 555-****  answer",
            "+44 20 ****  voicemail",
            "+44*",
            "*9999 ignore",
            "555-01??",
            "bogus line here",
            "88a8*"));

    private static void expect(String number, Rules.Action want, String rule) {
        Rules.Match m = RULES.match(number);
        assertEquals(number, want, m.action);
        assertEquals(number, rule, m.rule);
    }

    @Test public void parsesValidLinesAndReportsBadOnes() {
        assertEquals(8, RULES.rules.size());
        assertEquals(2, RULES.errors.size());
        assertEquals("Line 9: can't understand \"bogus line here\"", RULES.errors.get(0));
    }

    @Test public void nanpNumbersInAnyFormat() {
        expect("+18885550123", Rules.Action.IGNORE, "888*");
        expect("8885550123", Rules.Action.IGNORE, "888*");
        expect("1 (888) 555-0123", Rules.Action.IGNORE, "888*");
    }

    @Test public void leadingOneInPatternIsOptional() {
        expect("+18775550123", Rules.Action.VOICEMAIL, "1877*");
    }

    @Test public void firstMatchWins() {
        expect("+14045551234", Rules.Action.ALLOW, "4045551234");
        expect("4045559876", Rules.Action.ANSWER, "(404) 555-****");
    }

    @Test public void internationalPatterns() {
        expect("+442079460000", Rules.Action.VOICEMAIL, "+44 20 ****");
        expect("+447700900123", Rules.Action.IGNORE, "+44*");
        expect("011441234567", Rules.Action.IGNORE, "+44*");  // US-style international dialling
    }

    @Test public void suffixWildcard() {
        expect("+17705559999", Rules.Action.IGNORE, "*9999");
    }

    @Test public void wholeNumberMustMatch() {
        expect("+17705550123", Rules.Action.ALLOW, null);  // 7-digit pattern vs 10-digit number
        expect("+16785550123", Rules.Action.ALLOW, null);
    }

    @Test public void nanpPatternsDoNotMatchOtherCountries() {
        expect("+8885550123", Rules.Action.ALLOW, null);
    }

    @Test public void prettyPrinting() {
        assertEquals("(888) 555-0123", Rules.pretty("+18885550123"));
        assertEquals("+442079460000", Rules.pretty("+44 20 7946 0000"));
        assertEquals("(unknown)", Rules.pretty(null));
    }

    @Test public void emptyRules() {
        assertNull(Rules.parse("").match("8885550123").rule);
    }
}
