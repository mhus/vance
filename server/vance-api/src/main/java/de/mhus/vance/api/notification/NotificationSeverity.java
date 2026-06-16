package de.mhus.vance.api.notification;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * How loud / how alarming a {@link NotificationDto} should be rendered.
 *
 * <p>Drives client-side decisions about sound, icon color, and — on
 * platforms that support it — the OS-level notification "interruption
 * level". {@link #INFO} is the safe default for "just a heads-up" pings
 * ("process done"); {@link #WARN} for things the user should look at
 * soon ("waiting on user input"); {@link #ERROR} for failures.
 *
 * <p>Mapping reference (informational, clients pick what makes sense):
 * <ul>
 *   <li>Foot: ANSI color (cyan / yellow / red), all three trigger the
 *       terminal bell.</li>
 *   <li>Web: WebAudio beep pitch (~600 Hz / 900 Hz / 1200 Hz), toast
 *       background color, Browser-Notification {@code requireInteraction}
 *       on {@code ERROR} only.</li>
 *   <li>iOS (Capacitor LocalNotifications): {@code .passive} / default /
 *       {@code .timeSensitive} interruption level; default sound for
 *       INFO/WARN, critical-alert tone for ERROR (if app is entitled,
 *       otherwise default).</li>
 * </ul>
 */
@GenerateTypeScript("notification")
public enum NotificationSeverity {

    /** "Heads-up" — process done, batch finished, etc. The common case. */
    INFO,

    /** Needs attention soon — blocked, awaiting input, quota threshold. */
    WARN,

    /** Failure or escalation — script crashed, critical limit hit. */
    ERROR
}
