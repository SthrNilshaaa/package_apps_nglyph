package org.aspends.nglyphs.util;

import com.topjohnwu.superuser.Shell;

public class FileUtils {

    // Actual Hardware Paths
    private static final String PATH_ALL = "/sys/class/leds/aw210xx_led/all_white_leds_br";
    private static final String PATH_FRAME = "/sys/class/leds/aw210xx_led/frame_leds_effect";
    private static final String PATH_SINGLE = "/sys/class/leds/aw210xx_led/single_led_br";

    public static void writeLine(String fileName, String value) {
        if (!Shell.getShell().isRoot())
            return;

        // Execute write action as root
        Shell.cmd("echo '" + value + "' > " + fileName).submit();
    }

    public static void writeAllLed(int value) {
        writeLine(PATH_ALL, String.valueOf(value));
    }

    public static void writeFrameLed(int[] pattern) {
        if (pattern == null)
            return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pattern.length; i++) {
            sb.append(pattern[i]);
            if (i < pattern.length - 1) {
                sb.append(" ");
            }
        }
        writeLine(PATH_FRAME, sb.toString());
    }

    public static void writeSingleLed(int led, int brightness) {
        writeLine(PATH_SINGLE, led + " " + brightness);
    }
}
