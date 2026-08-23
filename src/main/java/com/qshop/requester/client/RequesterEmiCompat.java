package com.qshop.requester.client;

import java.lang.reflect.Field;

/** Keeps EMI from processing its hidden sidebar while the settings page is open. */
final class RequesterEmiCompat {
    private static final String EMI_CONFIG = "dev.emi.emi.config.EmiConfig";
    private static Boolean previousEnabled;

    private RequesterEmiCompat() {
    }

    static void setSettingsScreen(boolean settings) {
        try {
            Class<?> configClass = Class.forName(EMI_CONFIG);
            Field enabled = configClass.getField("enabled");
            if (settings) {
                if (previousEnabled == null) {
                    previousEnabled = enabled.getBoolean(null);
                }
                enabled.setBoolean(null, false);
            } else if (previousEnabled != null) {
                enabled.setBoolean(null, previousEnabled);
                previousEnabled = null;
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // EMI is optional; the requester must still work when it is absent.
        }
    }
}
