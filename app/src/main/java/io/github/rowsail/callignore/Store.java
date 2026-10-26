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
            "# examples:\n"
            + "# 877*  voicemail\n"
            + "# +44*  answer\n"
            + "888*  ignore\n";

    private final SharedPreferences p;

    Store(Context c) {
        p = c.getSharedPreferences("callignore", Context.MODE_PRIVATE);
    }

    boolean enabled() { return p.getBoolean("enabled", true); }
    void setEnabled(boolean v) { p.edit().putBoolean("enabled", v).apply(); }

    String rules() { return p.getString("rules", DEFAULT_RULES); }
    void setRules(String v) { p.edit().putString("rules", v).apply(); }

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
