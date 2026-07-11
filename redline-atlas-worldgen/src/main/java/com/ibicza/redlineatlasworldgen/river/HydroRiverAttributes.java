package com.ibicza.redlineatlasworldgen.river;

record HydroRiverAttributes(long riverId, long nextDownId, long mainRiverId,
                            int strahlerOrder, double dischargeCms, double catchmentSquareKm) {
    static HydroRiverAttributes fallback(long recordNumber) {
        return new HydroRiverAttributes(recordNumber, 0L, recordNumber, 1, 0.0D, 0.0D);
    }
}
