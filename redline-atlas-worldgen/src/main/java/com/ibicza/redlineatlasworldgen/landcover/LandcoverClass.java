package com.ibicza.redlineatlasworldgen.landcover;

public enum LandcoverClass {
    UNKNOWN(0, "unknown"),
    WATER(80, "water"),
    TREES(10, "trees"),
    GRASS(30, "grass"),
    SHRUB(20, "shrub"),
    CROPLAND(40, "cropland"),
    BARE_SPARSE(60, "bare_sparse"),
    SNOW_ICE(70, "snow_ice"),
    WETLAND(90, "wetland"),
    MANGROVE(95, "mangrove"),
    URBAN(50, "urban"),
    MOSS_LICHEN(100, "moss_lichen");

    private final int esaCode;
    private final String id;

    LandcoverClass(int esaCode, String id) {
        this.esaCode = esaCode;
        this.id = id;
    }

    public int esaCode() {
        return esaCode;
    }

    public String id() {
        return id;
    }

    public boolean isKnown() {
        return this != UNKNOWN;
    }

    public static LandcoverClass fromEsaWorldCoverCode(int code) {
        return switch (code) {
            case 10 -> TREES;
            case 20 -> SHRUB;
            case 30 -> GRASS;
            case 40 -> CROPLAND;
            case 50 -> URBAN;
            case 60 -> BARE_SPARSE;
            case 70 -> SNOW_ICE;
            case 80 -> WATER;
            case 90 -> WETLAND;
            case 95 -> MANGROVE;
            case 100 -> MOSS_LICHEN;
            default -> UNKNOWN;
        };
    }
}
