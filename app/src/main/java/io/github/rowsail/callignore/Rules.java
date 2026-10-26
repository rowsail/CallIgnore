// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.rowsail.callignore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Number patterns and matching. Pure Java so it can be tested off-device.
 *
 * One rule per line: {@code <pattern> [action] [# comment]}. First match wins.
 * Patterns without '+' are North American (NANP) numbers matched against the
 * 10-digit national number; a leading 1 is tolerated. Patterns with '+' are
 * matched against the full international number. '*' matches any run of
 * digits (including none), '?' exactly one digit. Spaces, dashes, dots and
 * parentheses are ignored.
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

    public static final class Rule {
        public final String text;     // pattern as the user wrote it
        public final Action action;
        final boolean international;
        final Pattern regex;

        Rule(String text, Action action, boolean international, Pattern regex) {
            this.text = text;
            this.action = action;
            this.international = international;
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
    public final List<String> errors = new ArrayList<>();

    public static Rules parse(String text) {
        Rules r = new Rules();
        if (text == null) return r;
        String[] lines = text.split("\n");
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;

            // The action, if present, is the last word; everything before it is the pattern.
            Action action = Action.IGNORE;
            String pattern = line;
            int sp = line.lastIndexOf(' ');
            int tab = line.lastIndexOf('\t');
            int cut = Math.max(sp, tab);
            if (cut > 0) {
                Action a = Action.fromKeyword(line.substring(cut + 1).trim());
                if (a != null) {
                    action = a;
                    pattern = line.substring(0, cut).trim();
                }
            }
            String compact = pattern.replaceAll("[\\s().\\-]", "");
            if (!compact.matches("\\+?[0-9*?]+") || compact.replace("+", "").isEmpty()) {
                r.errors.add("Line " + (n + 1) + ": can't understand \"" + lines[n].trim() + "\"");
                continue;
            }
            boolean intl = compact.startsWith("+");
            String body = intl ? compact.substring(1) : compact;
            // A NANP pattern written with its leading 1 ("1888*") means the same as "888*".
            if (!intl && body.length() > 1 && body.charAt(0) == '1' && body.charAt(1) != '*' && body.charAt(1) != '?')
                body = body.substring(1);
            StringBuilder re = new StringBuilder();
            for (char c : body.toCharArray())
                re.append(c == '*' ? "[0-9]*" : c == '?' ? "[0-9]" : String.valueOf(c));
            r.rules.add(new Rule(pattern, action, intl, Pattern.compile(re.toString())));
        }
        return r;
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
        for (Rule rule : rules) {
            String subject = rule.international ? intl : national;
            if (subject != null && rule.regex.matcher(subject).matches()) return new Match(rule.action, rule.text);
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
