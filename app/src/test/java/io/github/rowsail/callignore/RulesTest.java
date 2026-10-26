// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.rowsail.callignore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Off-device checks for rule parsing, matching and migration. */
public class RulesTest {
    private static final String CARIBBEAN = "^\\+?1?(268|284|473|649|664|767|784|809|829|849|876)\\d{7}$";

    private static final Rules WILDCARDS = Rules.parse(String.join("\n",
            "=4045551234 allow   # exception first",
            "=888*",
            "=1877*  voicemail",
            "= (404) 555-****  answer",
            "=+44 20 ****  voicemail",
            "=+44*",
            "=*9999 ignore",
            "=555-01??",
            "=bogus line here",
            "=88a8*"));

    private static void expect(Rules rules, String number, Rules.Action want, String rule) {
        Rules.Match m = rules.match(number);
        assertEquals(number, want, m.action);
        assertEquals(number, rule, m.rule);
    }

    // --- wildcard ("=") rules ---------------------------------------------------------

    @Test public void wildcardParsesValidLinesAndReportsBadOnes() {
        assertEquals(8, WILDCARDS.rules.size());
        assertEquals(2, WILDCARDS.errors.size());
        assertEquals("Line 9: can't understand wildcard pattern \"=bogus line here\"", WILDCARDS.errors.get(0));
    }

    @Test public void wildcardNanpNumbersInAnyFormat() {
        expect(WILDCARDS, "+18885550123", Rules.Action.IGNORE, "=888*");
        expect(WILDCARDS, "8885550123", Rules.Action.IGNORE, "=888*");
        expect(WILDCARDS, "1 (888) 555-0123", Rules.Action.IGNORE, "=888*");
    }

    @Test public void wildcardLeadingOneIsOptional() {
        expect(WILDCARDS, "+18775550123", Rules.Action.VOICEMAIL, "=1877*");
    }

    @Test public void firstMatchWins() {
        expect(WILDCARDS, "+14045551234", Rules.Action.ALLOW, "=4045551234");
        expect(WILDCARDS, "4045559876", Rules.Action.ANSWER, "= (404) 555-****");
    }

    @Test public void wildcardInternational() {
        expect(WILDCARDS, "+442079460000", Rules.Action.VOICEMAIL, "=+44 20 ****");
        expect(WILDCARDS, "+447700900123", Rules.Action.IGNORE, "=+44*");
        expect(WILDCARDS, "011441234567", Rules.Action.IGNORE, "=+44*");  // US-style international dialling
    }

    @Test public void wildcardSuffixAndWholeNumber() {
        expect(WILDCARDS, "+17705559999", Rules.Action.IGNORE, "=*9999");
        expect(WILDCARDS, "+17705550123", Rules.Action.ALLOW, null);      // 7-digit pattern vs 10-digit number
        expect(WILDCARDS, "+8885550123", Rules.Action.ALLOW, null);       // +888… isn't NANP
    }

    // --- regex rules (no prefix) ------------------------------------------------------

    private static final Rules REGEXES = Rules.parse(String.join("\n",
            "=4732223333 allow                 # a wildcard exception above still wins first",
            "# Caribbean area codes",
            CARIBBEAN + "  ignore",
            "^\\+44  voicemail",
            "5550199 answer                     # unanchored: matches anywhere",
            "^\\+33[#/]?\\d+$ allow             # '#' and '/' inside a regex need no escaping",
            "(unclosed",
            "888*",
            "^\\+49 (?:15|16)\\d+$ allow"));

    @Test public void regexFromTheExample() {
        expect(REGEXES, "+12685551234", Rules.Action.IGNORE, CARIBBEAN);
        expect(REGEXES, "2685551234", Rules.Action.IGNORE, CARIBBEAN);       // normalized to +1… first
        expect(REGEXES, "1 (876) 555-1234", Rules.Action.IGNORE, CARIBBEAN);
        expect(REGEXES, "+18095551234", Rules.Action.IGNORE, CARIBBEAN);
        expect(REGEXES, "+16785551234", Rules.Action.ALLOW, null);
        // Pitfall: with "1?" optional, Norway's +47 32 22 33 34 reads as "+" "473" "2223334".
        expect(REGEXES, "+4732223334", Rules.Action.IGNORE, CARIBBEAN);
    }

    @Test public void strictRegexAvoidsTheOptionalOnePitfall() {
        Rules strict = Rules.parse("^\\+1(268|284|473|649|664|767|784|809|829|849|876)\\d{7}$");
        assertEquals(Rules.Action.IGNORE, strict.match("2685551234").action);   // NANP always arrives as +1…
        assertEquals(Rules.Action.ALLOW, strict.match("+4732223333").action);
    }

    @Test public void wildcardAboveRegexWinsFirst() {
        expect(REGEXES, "+14732223333", Rules.Action.ALLOW, "=4732223333");
    }

    @Test public void regexActionsFindAndSpecialCharacters() {
        expect(REGEXES, "+442079460000", Rules.Action.VOICEMAIL, "^\\+44");
        expect(REGEXES, "+17705550199", Rules.Action.ANSWER, "5550199");
        expect(REGEXES, "+33123456", Rules.Action.ALLOW, "^\\+33[#/]?\\d+$");
    }

    @Test public void regexMayContainSpaces() {
        Rules.Rule last = REGEXES.rules.get(REGEXES.rules.size() - 1);
        assertEquals("^\\+49 (?:15|16)\\d+$", last.text);
        assertEquals(Rules.Action.ALLOW, last.action);
    }

    @Test public void regexErrorsAndWarnings() {
        assertEquals(REGEXES.errors.toString(), 1, REGEXES.errors.size());
        assertTrue(REGEXES.errors.get(0), REGEXES.errors.get(0).startsWith("Line 7: bad regex: Unclosed group"));
        assertEquals(REGEXES.warnings.toString(), 1, REGEXES.warnings.size());
        assertEquals("Line 8: \"888*\" is used as a regex; for wildcard matching write =888*", REGEXES.warnings.get(0));
        // ...and the warning is deserved: as a regex, 888* is "88" then any 8s, anywhere in the number.
        expect(REGEXES, "+17705550088", Rules.Action.IGNORE, "888*");
    }

    @Test public void invalidWildcardLikeRegexGetsHint() {
        Rules r = Rules.parse("+44*");
        assertEquals(0, r.rules.size());
        assertTrue(r.errors.get(0), r.errors.get(0).startsWith("Line 1: bad regex: Dangling meta character"));
        assertTrue(r.errors.get(0), r.errors.get(0).endsWith("; for wildcard matching write =+44*"));
    }

    @Test public void commentsNeedLeadingWhitespace() {
        assertEquals("abc ", Rules.stripComment("abc # note"));
        assertEquals("a#b", Rules.stripComment("a#b"));
        assertEquals("", Rules.stripComment("# whole line"));
    }

    // --- defaults and migration --------------------------------------------------------

    @Test public void defaultRulesParseCleanly() {
        Rules d = Rules.parse(defaultRules());
        assertEquals(d.errors.toString(), 0, d.errors.size());
        assertEquals(d.warnings.toString(), 0, d.warnings.size());
        expect(d, "+18765550123", Rules.Action.IGNORE, CARIBBEAN);
        expect(d, "+14045550123", Rules.Action.ALLOW, null);
    }

    private static final String PREMIUM = "^\\+1(900\\d{7}|\\d{3}976\\d{4})$";
    private static final String TOLL_FREE = "^\\+1(800|888|877|866|855|844|833)\\d{7}$";

    @Test public void defaultPremiumRateToVoicemail() {
        Rules d = Rules.parse(defaultRules());
        expect(d, "9005550123", Rules.Action.VOICEMAIL, PREMIUM);            // area code 900
        expect(d, "+1 404 976 1234", Rules.Action.VOICEMAIL, PREMIUM);       // 976 exchange, any area code
        expect(d, "(212) 976-0000", Rules.Action.VOICEMAIL, PREMIUM);
        expect(d, "+14045559760", Rules.Action.ALLOW, null);                 // 976 elsewhere in the number
        expect(d, "+19765550123", Rules.Action.ALLOW, null);                 // 976 as an area code isn't premium
    }

    @Test public void defaultTollFreeToVoicemail() {
        Rules d = Rules.parse(defaultRules());
        for (String code : new String[]{"800", "888", "877", "866", "855", "844", "833"})
            expect(d, code + "5550123", Rules.Action.VOICEMAIL, TOLL_FREE);
        expect(d, "+18225550123", Rules.Action.ALLOW, null);                 // 822 is reserved, not in the list
        expect(d, "+448005550123", Rules.Action.ALLOW, null);                // UK 0800 isn't NANP toll-free
    }

    @Test public void untouchedEarlierDefaultsAreUpgraded() {
        String v12 = "# Caribbean area codes (268 284 473 649 664 767 784 809 829 849 876): \"one-ring\" call-back scams\n"
                + "^\\+?1?(268|284|473|649|664|767|784|809|829|849|876)\\d{7}$  ignore\n"
                + "# '=' = simple wildcard pattern, e.g.:\n"
                + "# =877*  voicemail\n"
                + "# =+44*  answer\n"
                + "=888*  ignore\n";
        assertEquals(defaultRules(), Store.upgradeDefaults(v12));
        assertEquals(v12 + "=404*\n", Store.upgradeDefaults(v12 + "=404*\n"));  // edited: left alone
        assertEquals(defaultRules(), Store.upgradeDefaults(defaultRules()));
    }

    @Test public void migrationFromFormat1() {
        assertEquals(defaultRules(), Store.migrateV1("# examples:\n# 877*  voicemail\n# +44*  answer\n888*  ignore\n"));
        assertEquals("# mine\n=404555*  answer\n=+44*\n\n^\\+1(268|876)\\d{7}$ ignore\n",
                Store.migrateV1("# mine\n404555*  answer\n+44*\n\n/^\\+1(268|876)\\d{7}$/ ignore\n"));
        assertEquals("a/b allow", Store.migrateV1("/a\\/b/ allow"));
    }

    @Test public void prettyPrinting() {
        assertEquals("(888) 555-0123", Rules.pretty("+18885550123"));
        assertEquals("+442079460000", Rules.pretty("+44 20 7946 0000"));
        assertEquals("(unknown)", Rules.pretty(null));
    }

    @Test public void emptyRules() {
        assertNull(Rules.parse("").match("8885550123").rule);
    }

    /** Store's built-in default rules, read without needing an Android Context. */
    private static String defaultRules() {
        try {
            java.lang.reflect.Field f = Store.class.getDeclaredField("DEFAULT_RULES");
            f.setAccessible(true);
            return (String) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
