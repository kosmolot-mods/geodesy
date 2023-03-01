package pl.kosma.geodesy.projection;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Representation of a position on given plane.
 * Effectively, it is a BlockPos where a specific coordinate is reduced
 * to a plane and the remaining coordinates are stored.
 * @param plane The plane the slice acts upon.
 * @param a The first coordinate in a 2d grid across the plane.
 * @param b The second coordinate in a 2d grid across the plane.
 */
public record GeodesyPlanePos(PlaneEnum plane, int a, int b) {

    public static GeodesyPlanePos fromBlockPos(GeodesyBlockPos blockPos, PlaneEnum plane) {
        return switch (plane) {
            case X -> new GeodesyPlanePos(plane, blockPos.y(), blockPos.z());
            case Y -> new GeodesyPlanePos(plane, blockPos.x(), blockPos.z());
            case Z -> new GeodesyPlanePos(plane, blockPos.x(), blockPos.y());
        };
    }

    GeodesyPlanePos offset(int a, int b) {
        return new GeodesyPlanePos(this.plane, this.a + a, this.b + b);
    }

    public Stream<GeodesyPlanePos> neighbours() {
        return Stream.of(new int[][]{{0, 1}, {1, 0}, {0, -1}, {-1, 0}})
                .map(offset -> this.offset(offset[0], offset[1]));
    }
}
