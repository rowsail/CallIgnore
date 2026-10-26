// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.rowsail.callignore;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Settings, rules and the recent-calls log, all kept in app-private preferences. */
final class Store {
    static final int LOG_SIZE = 20;
    private static final String DEFAULT_RULES =
            "# Caribbean area codes (268 284 473 649 664 767 784 809 829 849 876): \"one-ring\" call-back scams\n"
            + "^\\+?1?(268|284|473|649|664|767|784|809|829|849|876)\\d{7}$  ignore\n"
            + "# Premium-rate: area code 900, and 976 local exchanges in any area code\n"
            + "^\\+1(900\\d{7}|\\d{3}976\\d{4})$  voicemail\n"
            + "# Toll-free (800 888 877 866 855 844 833)\n"
            + "^\\+1(800|888|877|866|855|844|833)\\d{7}$  voicemail\n"
            + "# '=' = simple wildcard pattern, e.g.:\n"
            + "# =404555*  answer\n"
            + "# =+44*  ignore\n";

    /**
     * Rule syntax version. 1: wildcard patterns bare (and briefly /regex/ in slashes);
     * 2: bare lines are regexes, wildcards need '='.
     */
    private static final int RULES_FORMAT = 2;
    private static final String[] V1_DEFAULTS = {
            "# examples:\n# 877*  voicemail\n# +44*  answer\n888*  ignore\n",
            "# One rule per line: pattern [action]   (first match wins)\n"
                    + "# Actions: ignore (default), voicemail, answer, allow\n"
                    + "# 888*           = US/Canada area code 888\n"
                    + "# +44*           = any UK number\n"
                    + "# 404555????     = ? is one digit\n"
                    + "888*  ignore\n",
    };
    /** Earlier format-2 default lists; a saved list still equal to one was never edited, so it's upgraded. */
    private static final String[] V2_DEFAULTS = {
            // 1.2
            "# Caribbean area codes (268 284 473 649 664 767 784 809 829 849 876): \"one-ring\" call-back scams\n"
                    + "^\\+?1?(268|284|473|649|664|767|784|809|829|849|876)\\d{7}$  ignore\n"
                    + "# '=' = simple wildcard pattern, e.g.:\n"
                    + "# =877*  voicemail\n"
                    + "# =+44*  answer\n"
                    + "=888*  ignore\n",
    };

    private final SharedPreferences p;

    Store(Context c) {
        p = c.getSharedPreferences("callignore", Context.MODE_PRIVATE);
    }

    boolean enabled() { return p.getBoolean("enabled", true); }
    void setEnabled(boolean v) { p.edit().putBoolean("enabled", v).apply(); }

    String rules() {
        String saved = p.getString("rules", null);
        if (saved == null) return DEFAULT_RULES;
        String upgraded = p.getInt("rulesFormat", 1) < RULES_FORMAT ? migrateV1(saved) : upgradeDefaults(saved);
        if (!upgraded.equals(saved)) setRules(upgraded);
        return upgraded;
    }

    /** An untouched earlier default list becomes the current one; anything the user edited is kept as is. */
    static String upgradeDefaults(String saved) {
        for (String d : V2_DEFAULTS) if (d.equals(saved)) return DEFAULT_RULES;
        return saved;
    }

    void setRules(String v) { p.edit().putString("rules", v).putInt("rulesFormat", RULES_FORMAT).apply(); }

    /** Format 1 → 2: unchanged defaults become the new defaults; bare wildcard lines gain '='; "/re/" loses its slashes. */
    static String migrateV1(String text) {
        for (String d : V1_DEFAULTS) if (d.equals(text)) return DEFAULT_RULES;
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                out.append(line);
            } else if (t.startsWith("/")) {
                int close = -1;
                for (int i = 1; i < t.length(); i++) {
                    if (t.charAt(i) == '\\') i++;
                    else if (t.charAt(i) == '/') { close = i; break; }
                }
                out.append(close < 0 ? t : t.substring(1, close).replace("\\/", "/") + t.substring(close + 1));
            } else {
                out.append('=').append(t);
            }
            out.append('\n');
        }
        return out.substring(0, out.length() - 1);
    }

    Rules.Action hiddenAction() {
        Rules.Action a = Rules.Action.fromKeyword(p.getString("hidden", "allow"));
        return a == null ? Rules.Action.ALLOW : a;
    }
    void setHiddenAction(Rules.Action a) { p.edit().putString("hidden", a.keyword).apply(); }

    /** Newest first; each entry is "when\tnumber\taction label\trule". */
    List<String[]> log() {
        List<String[]> out = new ArrayList<>();
        for (String line : p.getString("log", "").split("\n"))
            if (!line.isEmpty()) out.add(line.split("\t", -1));
        return out;
    }

    synchronized void addLog(String number, Rules.Action action, String rule, String note) {
        List<String> lines = new ArrayList<>(Arrays.asList(p.getString("log", "").split("\n")));
        lines.removeIf(String::isEmpty);
        String when = new SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(new Date());
        String what = action.label + (note == null ? "" : " (" + note + ")");
        lines.add(0, when + "\t" + number + "\t" + what + "\t" + (rule == null ? "" : rule));
        while (lines.size() > LOG_SIZE) lines.remove(lines.size() - 1);
        p.edit().putString("log", String.join("\n", lines)).apply();
    }

    void clearLog() { p.edit().remove("log").apply(); }
}
