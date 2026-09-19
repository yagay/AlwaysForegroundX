package com.yagay.MiniWindowGuard;

import java.io.BufferedReader;
import java.io.InputStreamReader;

final class RootManager {
    static final class RootStatus {
        final boolean granted;
        final String detail;

        RootStatus(boolean granted, String detail) {
            this.granted = granted;
            this.detail = detail;
        }
    }

    private RootManager() {}

    static RootStatus checkAccess() {
        String output = capture("id", 4096);
        boolean granted = output.startsWith("[exit=0]") && output.contains("uid=0");
        return new RootStatus(granted, output);
    }

    static boolean run(String command, StringBuilder detail) {
        String output = capture(command, 8192);
        boolean ok = output.startsWith("[exit=0]");
        if (detail != null) detail.append(output);
        return ok;
    }

    static String capture(String command, int maxChars) {
        java.lang.Process process = null;
        StringBuilder out = new StringBuilder();

        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (out.length() < maxChars) {
                        int remain = maxChars - out.length();
                        String append = line.length() > remain
                                ? line.substring(0, remain)
                                : line;
                        out.append(append).append('\n');
                    }
                }
            }

            int exit = process.waitFor();
            String body = out.toString();
            if (body.length() > maxChars) body = body.substring(0, maxChars);
            return "[exit=" + exit + "]\n" + body;
        } catch (Throwable t) {
            return "[exception=" + t.getClass().getName() + "] "
                    + t.getMessage() + "\n" + out;
        } finally {
            if (process != null) process.destroy();
        }
    }

    static String quote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
