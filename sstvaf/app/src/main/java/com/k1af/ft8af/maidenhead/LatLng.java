package com.k1af.ft8af.maidenhead;

import androidx.annotation.Nullable;

/**
 * Minimal immutable latitude/longitude pair.
 *
 * <p>Drop-in replacement for {@code com.google.android.gms.maps.model.LatLng},
 * introduced when the map features (and with them the play-services-maps
 * dependency) were removed. Only the surface the app actually uses is kept:
 * the two public final fields and a (lat, lng) constructor.
 */
public final class LatLng {
    public final double latitude;
    public final double longitude;

    public LatLng(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof LatLng)) return false;
        LatLng other = (LatLng) o;
        return Double.compare(latitude, other.latitude) == 0
                && Double.compare(longitude, other.longitude) == 0;
    }

    @Override
    public int hashCode() {
        int result = Double.hashCode(latitude);
        result = 31 * result + Double.hashCode(longitude);
        return result;
    }

    @Override
    public String toString() {
        return "lat/lng: (" + latitude + "," + longitude + ")";
    }
}
