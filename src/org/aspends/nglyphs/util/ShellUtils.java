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
    private static final ExecutorService shellExecutor = Executors.newSingleThreadExecutor();

    /**
     * Executes a command via su.
     * Use fastWrite for frequent sysfs updates.
     */
    public static synchronized void fastWrite(String path, String value) {
        executeCommand("echo " + value + " > " + path);
    }

    /**
     * Executes a raw command (or multiple commands separated by ;) via su.
     */
    public static synchronized void executeCommand(String command) {
        if (command == null || command.isEmpty()) return;
        shellExecutor.execute(() -> {
            try {
                ensureRootShell();
                if (suStream != null) {
                    suStream.writeBytes(command + "\n");
                    suStream.flush();
                }
            } catch (IOException e) {
                Log.e(TAG, "Root execution failed: " + command, e);
                closeShell();
            }
        });
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

    public static boolean isRootAvailable() {
        try {
            Process p = Runtime.getRuntime().exec("su -c id");
            int exitCode = p.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
