package com.yagay.alwaysforeground;

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
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(true)
                    .start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (out.length() < 512) {
                        if (out.length() > 0) out.append(' ');
                        out.append(line);
                    }
                }
            }
            int exit = process.waitFor();
            boolean granted = exit == 0 && out.toString().contains("uid=0");
            return new RootStatus(granted, out.toString().trim());
        } catch (Throwable t) {
            return new RootStatus(false, t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (process != null) process.destroy();
        }
    }

    static boolean run(String command, StringBuilder detail) {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (out.length() < 1024) {
                        if (out.length() > 0) out.append(" | ");
                        out.append(line);
                    }
                }
            }
            int exit = process.waitFor();
            if (detail != null) {
                detail.append("exit=").append(exit);
                if (out.length() > 0) detail.append(" ").append(out);
            }
            return exit == 0;
        } catch (Throwable t) {
            if (detail != null) {
                detail.append(t.getClass().getSimpleName())
                        .append(": ")
                        .append(t.getMessage());
            }
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    static String quote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
