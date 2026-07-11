package com.ibicza.redlineatlasworldgen.river;

import java.util.List;

record RawHydroRiver(String sourceId, HydroRiverAttributes attributes, List<GeoRiverPoint> points) {
}
