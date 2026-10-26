// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.rowsail.callignore;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.CallScreeningService;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * Screens incoming calls against the user's rules.
 *
 * IGNORE    - block without rejecting: no ring, no UI; the network keeps ringing
 *             the caller until they give up (or no-answer forwarding kicks in).
 * VOICEMAIL - block and reject, which carriers route to voicemail.
 * ANSWER    - let the call through silently, answer it and hang up at once, so it
 *             never reaches voicemail.
 */
public class IgnoreService extends CallScreeningService {
    private static final String TAG = "CallIgnore";
    private static final long POLL_MS = 50, GIVE_UP_MS = 5000;

    @Override
    public void onScreenCall(Call.Details details) {
        Store store = new Store(this);
        if (details.getCallDirection() != Call.Details.DIRECTION_INCOMING || !store.enabled()) {
            respondToCall(details, new CallResponse.Builder().build());
            return;
        }
        Uri handle = details.getHandle();
        String number = handle == null ? null : handle.getSchemeSpecificPart();
        boolean hidden = number == null || number.isEmpty()
                || details.getHandlePresentation() != TelecomManager.PRESENTATION_ALLOWED;

        Rules.Match m = hidden
                ? new Rules.Match(store.hiddenAction(), "hidden/withheld")
                : Rules.parse(store.rules()).match(number);
        String shown = hidden ? "Hidden number" : Rules.pretty(number);
        Log.i(TAG, "screen " + shown + " -> " + m.action + " (" + m.rule + ")");

        switch (m.action) {
            case ALLOW:
                respondToCall(details, new CallResponse.Builder().build());
                return;
            case VOICEMAIL:
                respondToCall(details, blocked(true));
                store.addLog(shown, m.action, m.rule, null);
                return;
            case ANSWER:
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
                        && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    respondToCall(details, new CallResponse.Builder().setSilenceCall(true).build());
                    answerThenHangUp();
                    store.addLog(shown, m.action, m.rule, null);
                    return;
                }
                // Without permission to answer, fall back to ignoring.
                respondToCall(details, blocked(false));
                store.addLog(shown, Rules.Action.IGNORE, m.rule, "answer permission missing");
                return;
            case IGNORE:
            default:
                respondToCall(details, blocked(false));
                store.addLog(shown, Rules.Action.IGNORE, m.rule, null);
        }
    }

    private static CallResponse blocked(boolean reject) {
        return new CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(reject)
                .setSkipCallLog(true)
                .setSkipNotification(true)
                .build();
    }

    /**
     * Answer as soon as the call is ringing, then hang up the moment it stops
     * ringing. endCall() on a call that is still ringing would reject it (and
     * send it to voicemail), so the hang-up waits for the ringing to end.
     */
    // acceptRingingCall/endCall are the only non-system way to do this; getCallState is
    // the device-wide state (getCallStateForSubscription would miss calls on the other SIM).
    @SuppressWarnings("deprecation")
    private void answerThenHangUp() {
        TelecomManager tm = getSystemService(TelecomManager.class);
        TelephonyManager phone = getSystemService(TelephonyManager.class);
        Handler h = new Handler(Looper.getMainLooper());
        long deadline = System.currentTimeMillis() + GIVE_UP_MS;
        h.post(new Runnable() {
            boolean answered;

            @Override
            public void run() {
                try {
                    int state = phone.getCallState();
                    if (state == TelephonyManager.CALL_STATE_RINGING) {
                        if (!answered) {
                            tm.acceptRingingCall();
                            answered = true;
                            Log.i(TAG, "answered");
                        }
                    } else if (answered) {
                        // OFFHOOK: connected, hang up now. IDLE: the caller already gave up.
                        if (state == TelephonyManager.CALL_STATE_OFFHOOK) Log.i(TAG, "endCall -> " + tm.endCall());
                        return;
                    }
                } catch (SecurityException e) {
                    Log.w(TAG, "answer/hang up", e);
                    return;
                }
                if (System.currentTimeMillis() < deadline) h.postDelayed(this, POLL_MS);
                else Log.w(TAG, "gave up: call never " + (answered ? "connected" : "rang"));
            }
        });
    }
}
