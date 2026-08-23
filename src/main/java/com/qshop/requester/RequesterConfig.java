package com.qshop.requester;

import net.minecraftforge.common.ForgeConfigSpec;

/** Forge COMMON config stored at config/qshop_requester-common.toml. */
public final class RequesterConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ENABLE_LAYOUT_DEBUG = BUILDER
            .comment("DEBUG ONLY. Enables the F8 GUI layout editor. Disabled by default.")
            .define("enableLayoutDebug", false);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private RequesterConfig() {}

    public static boolean layoutDebugEnabled() {
        return ENABLE_LAYOUT_DEBUG.get();
    }
}
