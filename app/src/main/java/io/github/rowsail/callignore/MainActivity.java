// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.rowsail.callignore;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Single settings screen, built in code so the app needs no layout resources. */
public class MainActivity extends Activity {
    private static final int REQ_ROLE = 1, REQ_ANSWER = 2;
    private static final String[] ANSWER_PERMS = {
            Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE};

    private Store store;
    private LinearLayout root;
    private TextView status, ruleCheck, testResult, logView;
    private EditText rulesEdit, testNumber;
    private Button roleButton, answerButton;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        Switch enabled = new Switch(this);
        enabled.setText("Screening enabled");
        enabled.setTextSize(16);
        enabled.setChecked(store.enabled());
        enabled.setOnCheckedChangeListener((v, on) -> store.setEnabled(on));
        root.addView(enabled);

        status = text("", 14);
        root.addView(status, lp(dp(8)));
        roleButton = button("Make Call Ignore the call-screening app", v -> requestRole());
        answerButton = button("Allow \"answer & hang up\"", v ->
                requestPermissions(ANSWER_PERMS, REQ_ANSWER));

        header("Hidden / withheld numbers");
        Spinner hidden = new Spinner(this);
        Rules.Action[] actions = Rules.Action.values();
        String[] labels = new String[actions.length];
        for (int i = 0; i < actions.length; i++) labels[i] = actions[i].label;
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        hidden.setAdapter(ad);
        hidden.setSelection(store.hiddenAction().ordinal());
        hidden.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { store.setHiddenAction(actions[pos]); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        root.addView(hidden);

        header("Rules");
        root.addView(text("One per line: pattern [action]   # comment. First match wins.\n"
                + "Actions: ignore (default) · voicemail · answer · allow\n"
                + "Pattern = regular expression, searched in the number as +E.164 (e.g. +18885550123).\n"
                + "=pattern = simple wildcard: * any digits, ? one digit;\n"
                + "  no '+' = US/Canada (=888* is area code 888), '+' = international (=+44*).\n"
                + "Contacts are never screened.", 13));
        rulesEdit = new EditText(this);
        rulesEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        rulesEdit.setTypeface(Typeface.MONOSPACE);
        rulesEdit.setTextSize(14);
        rulesEdit.setGravity(Gravity.TOP);
        rulesEdit.setMinLines(6);
        rulesEdit.setHorizontallyScrolling(true);
        rulesEdit.setText(store.rules());
        rulesEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable s) { checkRules(); }
        });
        root.addView(rulesEdit, lp(dp(4)));
        ruleCheck = text("", 13);
        root.addView(ruleCheck);
        button("Save rules", v -> {
            store.setRules(rulesEdit.getText().toString());
            Toast.makeText(this, "Rules saved", Toast.LENGTH_SHORT).show();
            runTest();
        });

        header("Test a number");
        testNumber = new EditText(this);
        testNumber.setInputType(InputType.TYPE_CLASS_PHONE);
        testNumber.setHint("e.g. 888 555 0123");
        testNumber.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable s) { runTest(); }
        });
        root.addView(testNumber);
        testResult = text("", 14);
        root.addView(testResult);

        header("Last " + Store.LOG_SIZE + " screened calls");
        logView = text("", 13);
        logView.setTypeface(Typeface.MONOSPACE);
        root.addView(logView);
        button("Clear log", v -> { store.clearLog(); showLog(); });

        checkRules();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        showLog();
    }

    private void refreshStatus() {
        RoleManager rm = getSystemService(RoleManager.class);
        boolean role = rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING);
        boolean answer = true;
        for (String perm : ANSWER_PERMS)
            answer &= checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
        status.setText((role ? "✓ Screening incoming calls" : "✗ Not the call-screening app — calls are NOT screened")
                + "\n" + (answer ? "✓ Can answer & hang up" : "– \"answer\" rules fall back to ignore until allowed"));
        roleButton.setVisibility(role ? View.GONE : View.VISIBLE);
        answerButton.setVisibility(answer ? View.GONE : View.VISIBLE);
    }

    private void requestRole() {
        RoleManager rm = getSystemService(RoleManager.class);
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), REQ_ROLE);
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        refreshStatus();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        refreshStatus();
    }

    private void checkRules() {
        Rules r = Rules.parse(rulesEdit.getText().toString());
        boolean dirty = !rulesEdit.getText().toString().equals(store.rules());
        String msg = r.rules.size() + " rule" + (r.rules.size() == 1 ? "" : "s")
                + (dirty ? " · unsaved changes" : " · saved");
        if (!r.errors.isEmpty()) msg += "\n✗ " + String.join("\n✗ ", r.errors);
        if (!r.warnings.isEmpty()) msg += "\n⚠ " + String.join("\n⚠ ", r.warnings);
        ruleCheck.setText(msg);
        runTest();
    }

    private void runTest() {
        if (testNumber == null) return;
        String n = testNumber.getText().toString().trim();
        if (n.isEmpty()) { testResult.setText(""); return; }
        Rules.Match m = Rules.parse(rulesEdit.getText().toString()).match(n);
        testResult.setText(Rules.pretty(n) + " → " + m.action.label
                + (m.rule == null ? " (no rule matched)" : " (rule: " + m.rule + ")"));
    }

    private void showLog() {
        List<String[]> log = store.log();
        if (log.isEmpty()) { logView.setText("(none yet)"); return; }
        StringBuilder sb = new StringBuilder();
        for (String[] e : log) {
            sb.append(e[0]).append("  ").append(e.length > 1 ? e[1] : "").append('\n')
              .append("    ").append(e.length > 2 ? e[2] : "");
            if (e.length > 3 && !e[3].isEmpty()) sb.append("  [").append(e[3]).append(']');
            sb.append('\n');
        }
        logView.setText(sb.toString().trim());
    }

    private void header(String s) {
        TextView t = text(s, 17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(t, lp(dp(20)));
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        root.addView(b, lp(dp(4)));
        return b;
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
