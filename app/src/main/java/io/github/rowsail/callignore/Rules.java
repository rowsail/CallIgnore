// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.rowsail.callignore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Number patterns and matching. Pure Java so it can be tested off-device.
 *
 * One rule per line: {@code <pattern> [action] [# comment]}. First match wins.
 * A '#' starts a comment only at the start of a line or after whitespace.
 *
 * A pattern is a Java regular expression, searched (find, not whole-string
 * match) in the number's E.164 form: "+" and the international digits, e.g.
 * "+18885550123".
 *
 * A pattern starting with '=' uses simple wildcard matching instead. Without
 * '+' it is a North American (NANP) pattern matched against the 10-digit
 * national number (a leading 1 is tolerated); with '+' it is matched against
 * the full international number. '*' matches any run of digits (including
 * none), '?' exactly one digit. Spaces, dashes, dots and parentheses are ignored.
 */
public final class Rules {
    public enum Action {
        ALLOW("allow", "Ring normally"),
        IGNORE("ignore", "Ignore silently"),
        VOICEMAIL("voicemail", "Send to voicemail"),
        ANSWER("answer", "Answer & hang up");

        public final String keyword, label;

        Action(String keyword, String label) {
            this.keyword = keyword;
            this.label = label;
        }

        public static Action fromKeyword(String s) {
            for (Action a : values()) if (a.keyword.equalsIgnoreCase(s)) return a;
            return null;
        }
    }

    /** What a rule's pattern is tested against. */
    enum Kind {
        NANP,           // wildcard pattern vs the 10-digit national number
        INTERNATIONAL,  // wildcard pattern vs the international digits
        REGEX           // user regex, searched in "+<international digits>"
    }

    public static final class Rule {
        public final String text;     // pattern as the user wrote it
        public final Action action;
        final Kind kind;
        final Pattern regex;

        Rule(String text, Action action, Kind kind, Pattern regex) {
            this.text = text;
            this.action = action;
            this.kind = kind;
            this.regex = regex;
        }
    }

    public static final class Match {
        public final Action action;
        public final String rule;     // null when nothing matched

        public Match(Action action, String rule) {
            this.action = action;
            this.rule = rule;
        }
    }

    public final List<Rule> rules = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();    // lines that were skipped
    public final List<String> warnings = new ArrayList<>();  // lines kept, but probably not what was meant

    /** Digits plus '*' or '?' and nothing else: without '=' this was probably meant as a wildcard pattern. */
    private static final Pattern LOOKS_LIKE_WILDCARD = Pattern.compile("[+0-9() .\\-]*[*?][+0-9*?() .\\-]*");

    public static Rules parse(String text) {
        Rules r = new Rules();
        if (text == null) return r;
        String[] lines = text.split("\n");
        for (int n = 0; n < lines.length; n++) {
            String line = stripComment(lines[n]).trim();
            if (line.isEmpty()) continue;
            String where = "Line " + (n + 1) + ": ";

            // The action, if present, is the last word; everything before it is the pattern.
            Action action = Action.IGNORE;
            String pattern = line;
            int cut = Math.max(line.lastIndexOf(' '), line.lastIndexOf('\t'));
            if (cut > 0) {
                Action a = Action.fromKeyword(line.substring(cut + 1).trim());
                if (a != null) {
                    action = a;
                    pattern = line.substring(0, cut).trim();
                }
            }
            if (pattern.startsWith("=")) parseWildcard(r, where, pattern, action);
            else parseRegex(r, where, pattern, action);
        }
        return r;
    }

    /** Drop a '#' comment; '#' counts only at the start or after whitespace, so regexes may contain it. */
    static String stripComment(String line) {
        for (int i = 0; i < line.length(); i++)
            if (line.charAt(i) == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1))))
                return line.substring(0, i);
        return line;
    }

    private static void parseWildcard(Rules r, String where, String pattern, Action action) {
        String compact = pattern.substring(1).replaceAll("[\\s().\\-]", "");
        if (!compact.matches("\\+?[0-9*?]+") || compact.replace("+", "").isEmpty()) {
            r.errors.add(where + "can't understand wildcard pattern \"" + pattern + "\"");
            return;
        }
        boolean intl = compact.startsWith("+");
        String body = intl ? compact.substring(1) : compact;
        // A NANP pattern written with its leading 1 ("=1888*") means the same as "=888*".
        if (!intl && body.length() > 1 && body.charAt(0) == '1' && body.charAt(1) != '*' && body.charAt(1) != '?')
            body = body.substring(1);
        StringBuilder re = new StringBuilder();
        for (char c : body.toCharArray())
            re.append(c == '*' ? "[0-9]*" : c == '?' ? "[0-9]" : String.valueOf(c));
        r.rules.add(new Rule(pattern, action, intl ? Kind.INTERNATIONAL : Kind.NANP, Pattern.compile(re.toString())));
    }

    private static void parseRegex(Rules r, String where, String pattern, Action action) {
        boolean wildcardish = LOOKS_LIKE_WILDCARD.matcher(pattern).matches();
        String hint = "; for wildcard matching write =" + pattern;
        try {
            r.rules.add(new Rule(pattern, action, Kind.REGEX, Pattern.compile(pattern)));
        } catch (PatternSyntaxException e) {
            r.errors.add(where + "bad regex: " + e.getDescription()
                    + (e.getIndex() >= 0 ? " near position " + (e.getIndex() + 1) : "")
                    + (wildcardish ? hint : ""));
            return;
        }
        if (wildcardish) r.warnings.add(where + "\"" + pattern + "\" is used as a regex" + hint);
    }

    /** Full international digits (no '+'), assuming NANP for 10-digit numbers. */
    public static String international(String number) {
        if (number == null) return "";
        String d = number.replaceAll("[^0-9]", "");
        if (number.trim().startsWith("+")) return d;
        if (d.length() == 10) return "1" + d;
        if (d.length() == 11 && d.startsWith("1")) return d;
        if (d.startsWith("011")) return d.substring(3);
        return d;
    }

    /** 10-digit national number for NANP numbers, otherwise null. */
    static String nanp(String intl) {
        return intl.length() == 11 && intl.startsWith("1") ? intl.substring(1) : null;
    }

    public Match match(String number) {
        String intl = international(number);
        String national = nanp(intl);
        String e164 = intl.isEmpty() ? null : "+" + intl;
        for (Rule rule : rules) {
            boolean hit;
            switch (rule.kind) {
                case REGEX:
                    hit = e164 != null && rule.regex.matcher(e164).find();
                    break;
                case INTERNATIONAL:
                    hit = rule.regex.matcher(intl).matches();
                    break;
                case NANP:
                default:
                    hit = national != null && rule.regex.matcher(national).matches();
            }
            if (hit) return new Match(rule.action, rule.text);
        }
        return new Match(Action.ALLOW, null);
    }

    public static String pretty(String number) {
        String intl = international(number);
        String n = nanp(intl);
        if (n != null) return String.format(Locale.US, "(%s) %s-%s", n.substring(0, 3), n.substring(3, 6), n.substring(6));
        return intl.isEmpty() ? "(unknown)" : "+" + intl;
    }
}
