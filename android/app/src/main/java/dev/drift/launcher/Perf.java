package dev.drift.launcher;

import android.os.Process;
import android.os.SystemClock;

import java.util.ArrayDeque;

/**
 * Last few lifecycle/frame timings, shown in the diagnostics report. Deliberately
 * in-memory only: it must never add disk I/O to the resume path it measures.
 */
final class Perf {
    private static final long PROCESS_START = Process.getStartUptimeMillis();
    private static final ArrayDeque<String> MARKS = new ArrayDeque<>();
    private static long lastMark = PROCESS_START;
    private static int creates;
    private static int resumes;
    private static int pauses;
    private static long frames;
    private static long renderTotalNs;
    private static long renderMaxNs;
    private static long slowFrames;
    private static boolean firstDrawMarked;

    private Perf() {}

    static synchronized void mark(String event) {
        long now = SystemClock.uptimeMillis();
        long sinceProcess = Math.max(0, now - PROCESS_START);
        String line = sinceProcess + " ms (+" + Math.max(0, now - lastMark) + " ms)  " + event;
        lastMark = now;
        MARKS.addLast(line);
        while (MARKS.size() > 40) MARKS.removeFirst();
    }

    static void created() {
        synchronized (Perf.class) { creates++; }
        mark("LauncherActivity.onCreate");
    }

    static void resumed() {
        synchronized (Perf.class) { resumes++; }
        mark("onResume");
    }

    static void paused() {
        synchronized (Perf.class) { pauses++; }
        mark("onPause");
    }

    static synchronized void firstDraw() {
        if (firstDrawMarked) return;
        firstDrawMarked = true;
        mark("first canvas draw");
    }

    static synchronized void frame(long renderNs) {
        frames++;
        renderTotalNs += renderNs;
        if (renderNs > renderMaxNs) renderMaxNs = renderNs;
        if (renderNs > 16_000_000L) slowFrames++;
    }

    static synchronized String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("activity creates: ").append(creates)
            .append(", resumes: ").append(resumes)
            .append(", pauses: ").append(pauses).append('\n');
        if (frames > 0) {
            double avg = renderTotalNs / 1_000_000.0 / frames;
            double max = renderMaxNs / 1_000_000.0;
            sb.append("native render: frames=").append(frames)
                .append(", avg=").append(String.format(java.util.Locale.ROOT, "%.2f", avg))
                .append(" ms, max=").append(String.format(java.util.Locale.ROOT, "%.2f", max))
                .append(" ms, >16 ms=").append(slowFrames).append('\n');
        }
        sb.append("last events:\n");
        for (String line : MARKS) sb.append("  ").append(line).append('\n');
        return sb.toString();
    }
}
