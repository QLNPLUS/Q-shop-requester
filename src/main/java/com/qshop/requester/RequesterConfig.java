package com.qshop.requester;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Forge COMMON config stored at config/qshop_requester-common.toml. */
public final class RequesterConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLE_LAYOUT_DEBUG = BUILDER
            .comment("DEBUG ONLY. Enables the F8 GUI layout editor. Disabled by default.")
            .define("enableLayoutDebug", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private RequesterConfig() {}

    public static boolean layoutDebugEnabled() {
        return ENABLE_LAYOUT_DEBUG.get();
    }
}
