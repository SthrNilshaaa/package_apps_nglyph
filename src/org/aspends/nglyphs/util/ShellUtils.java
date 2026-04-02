package org.aspends.nglyphs.util;

import android.util.Log;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages a persistent root shell (su) for low-latency hardware control.
 */
public class ShellUtils {
    private static final String TAG = "ShellUtils";
    private static Process suProcess;
    private static DataOutputStream suStream;
    private static final java.util.concurrent.LinkedBlockingQueue<Runnable> queue = new java.util.concurrent.LinkedBlockingQueue<>();
    private static final ExecutorService shellExecutor = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, queue);

    /**
     * Executes a command via direct sysfs write if possible, falling back to su.
     */
    public static void fastWrite(String path, String value) {
        shellExecutor.execute(() -> {
            if (tryDirectWrite(path, value)) {
                return;
            }
            executeCommandInternal("echo " + value + " > " + path);
        });
    }

    /**
     * Executes multiple writes efficiently.
     */
    public static void fastWriteBatch(java.util.Map<String, String> updates) {
        if (updates == null || updates.isEmpty()) return;
        shellExecutor.execute(() -> {
            boolean allDirectOk = true;
            for (java.util.Map.Entry<String, String> entry : updates.entrySet()) {
                if (!tryDirectWrite(entry.getKey(), entry.getValue())) {
                    allDirectOk = false;
                    // If one fails, we might as well do the rest via root or just continue trying direct
                }
            }
            if (allDirectOk) return;

            // Fallback for those that failed (or just redo all via root for atomic-ish simplicity)
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, String> entry : updates.entrySet()) {
                sb.append("echo ").append(entry.getValue()).append(" > ").append(entry.getKey()).append("; ");
            }
            executeCommandInternal(sb.toString());
        });
    }

    private static boolean tryDirectWrite(String path, String value) {
        java.io.File file = new java.io.File(path);
        if (!file.exists() || !file.canWrite()) return false;
        
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            fos.write(value.getBytes());
            fos.flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Executes a raw command via su.
     */
    public static synchronized void executeCommand(String command) {
        if (command == null || command.isEmpty()) return;
        shellExecutor.execute(() -> executeCommandInternal(command));
    }

    private static void executeCommandInternal(String command) {
        try {
            ensureRootShell();
            if (suStream != null) {
                suStream.writeBytes(command + "\n");
                suStream.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "Root execution failed: " + command, e);
            closeShell();
        }
    }

    /**
     * Clears all pending commands in the queue to ensure the next command executes immediately.
     * Useful for starting new animations or stopping effects without delay.
     */
    public static void clearQueue() {
        queue.clear();
    }

    private static void ensureRootShell() throws IOException {
        if (suProcess == null || suStream == null) {
            Log.d(TAG, "Starting new root shell session...");
            suProcess = Runtime.getRuntime().exec("su");
            suStream = new DataOutputStream(suProcess.getOutputStream());
        }
    }

    public static void closeShell() {
        if (suStream != null) {
            try {
                suStream.writeBytes("exit\n");
                suStream.flush();
                suStream.close();
            } catch (IOException ignored) {}
            suStream = null;
        }
        if (suProcess != null) {
            suProcess.destroy();
            suProcess = null;
        }
    }

    private static Boolean rootCache = null;

    public static boolean isRootAvailable() {
        if (rootCache != null && rootCache) return true;
        
        try {
            Process p = Runtime.getRuntime().exec("su -c id");
            // Add a timeout for safety
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroy();
                    return false;
                }
            } else {
                p.waitFor();
            }
            boolean available = (p.exitValue() == 0);
            if (available) rootCache = true;
            return available;
        } catch (Exception e) {
            return false;
        }
    }
}
