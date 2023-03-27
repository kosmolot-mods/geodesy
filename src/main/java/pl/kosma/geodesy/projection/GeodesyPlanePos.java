package pl.kosma.geodesy.projection;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.stream.Stream;

/**
 * Representation of a position on given plane.
 * Effectively, it is a BlockPos where a specific coordinate is reduced
 * to a plane and the remaining coordinates are stored.
 * @param plane The plane the slice acts upon.
 * @param a The first coordinate in a 2d grid across the plane.
 * @param b The second coordinate in a 2d grid across the plane.
 */
public record GeodesyPlanePos(Direction.Axis plane, int a, int b) {

    public static GeodesyPlanePos fromBlockPos(GeodesyBlockPos blockPos, Direction.Axis plane) {
        return switch (plane) {
            case X -> new GeodesyPlanePos(plane, blockPos.y(), blockPos.z());
            case Y -> new GeodesyPlanePos(plane, blockPos.x(), blockPos.z());
            case Z -> new GeodesyPlanePos(plane, blockPos.x(), blockPos.y());
        };
    }

    GeodesyPlanePos offset(int a, int b) {
        return new GeodesyPlanePos(this.plane, this.a + a, this.b + b);
    }
    
    GeodesyPlanePos offsetHorizontalPlane(int y, int xOrZ) {
        return switch (plane) {
            case X -> new GeodesyPlanePos(this.plane, this.a + y, this.b + xOrZ);
            case Z -> new GeodesyPlanePos(this.plane, this.a + xOrZ, this.b + y);
            case Y -> throw new RuntimeException("OffsetHorizontalPlane called with a non-horizontal plane!");
        };
    }

    public Stream<GeodesyPlanePos> neighbours() {
        return Stream.of(new int[][]{{0, 1}, {1, 0}, {0, -1}, {-1, 0}})
                .map(offset -> this.offset(offset[0], offset[1]));
    }

    public BlockPos toBlockPos(int x, int y, int z) {
        return switch (this.plane()) {
            case X -> new BlockPos(x, this.a + y, this.b + z);
            case Y -> new BlockPos(this.a + x, y, this.b + z);
            case Z -> new BlockPos(this.a + x, this.b + y, z);
        };
    }
}
