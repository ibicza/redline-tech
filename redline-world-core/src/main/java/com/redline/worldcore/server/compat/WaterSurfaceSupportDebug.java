package com.redline.worldcore.server.compat;

/** Runtime counters for the M16.1 water-surface-support fluid behavior override. */
public final class WaterSurfaceSupportDebug {
    private static long horizontalSupportChecks;
    private static long waterHolesBlocked;
    private static long downflowsBlocked;
    private static long scheduledPostMaterializeTicks;

    private WaterSurfaceSupportDebug() {
    }

    public static void recordHorizontalSupportCheck() {
        horizontalSupportChecks++;
    }

    public static void recordWaterHoleBlocked() {
        waterHolesBlocked++;
    }

    public static void recordDownflowBlocked() {
        downflowsBlocked++;
    }

    public static void recordScheduledPostMaterializeTick() {
        scheduledPostMaterializeTicks++;
    }

    public static long horizontalSupportChecks() {
        return horizontalSupportChecks;
    }

    public static long waterHolesBlocked() {
        return waterHolesBlocked;
    }

    public static long downflowsBlocked() {
        return downflowsBlocked;
    }

    public static long scheduledPostMaterializeTicks() {
        return scheduledPostMaterializeTicks;
    }

    public static void reset() {
        horizontalSupportChecks = 0L;
        waterHolesBlocked = 0L;
        downflowsBlocked = 0L;
        scheduledPostMaterializeTicks = 0L;
    }
}
