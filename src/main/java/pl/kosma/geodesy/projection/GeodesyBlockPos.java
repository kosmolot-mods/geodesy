package pl.kosma.geodesy.projection;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Minecraft-independent representation of BlockPos.
 */
public record GeodesyBlockPos(int x, int y, int z) {

    public GeodesyBlockPos offset(int x, int y, int z) {
        return new GeodesyBlockPos(this.x + x, this.y + y, this.z + z);
    }

    public Stream<GeodesyBlockPos> neighbours() {
        return Stream.of(new int[][]{{0, 0, 1}, {0, 1, 0}, {1, 0, 0}, {0, 0, -1}, {0, -1, 0}, {-1, 0, 0}})
                .map(offset -> this.offset(offset[0], offset[1], offset[2]));
    }
}
