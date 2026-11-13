package dev.drift.launcher;

import android.os.Process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Background executors with a low priority, so decoding never competes with the canvas. */
final class Workers {
    private Workers() {}

    static ExecutorService single(String name) {
        return Executors.newSingleThreadExecutor(factory(name));
    }

    static ExecutorService fixed(String name, int threads) {
        return Executors.newFixedThreadPool(Math.max(1, threads), factory(name));
    }

    private static ThreadFactory factory(String name) {
        return task -> new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, name);
    }
}
