package pl.kosma.geodesy.clustering;

import net.minecraft.util.math.Direction;
import org.apache.commons.lang3.stream.Streams;
import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.AnsiConsole;
import pl.kosma.geodesy.projection.GeodesyPlanePos;
import pl.kosma.geodesy.utils.NestedListIterator;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static pl.kosma.geodesy.GeodesyAssertionFailedException.geodesyAssert;

public class Clustering {

    private static final int MAX_GROUP_SIZE = 10;

    private final Direction.Axis plane;
    private List<List<Cell>> grid;

    private int minCoordA;
    private int minCoordB;
    private int maxCoordA;
    private int maxCoordB;
    private final int gridLenA;
    private final int gridLenB;

    public Clustering(Direction.Axis plane, Map<GeodesyPlanePos, Boolean> map) {
        this.plane = plane;
        setGridDimensions(map);
        this.gridLenA = maxCoordA - minCoordA + 1;
        this.gridLenB = maxCoordB - minCoordB + 1;
        initGrid(map);
    }

    public static Map<Direction.Axis, Clustering> clusters(Map<GeodesyPlanePos, Boolean> map) {
        return Arrays.stream(Direction.Axis.values())
                .collect(Collectors.toMap(
                        plane -> plane,
                        plane -> new Clustering(plane, map.entrySet().stream()
                                .filter(entry -> entry.getKey().plane().equals(plane))
                                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))));
    }

    /**
     * Derive the dimensions of the grid based on the minimum and maximum positions found in {@code map}.
     * A row and column is added in each direction of the grid.
     * @param map A mapping from geodesy plane positions (slices) to a boolean indicating whether they are active.
     */
    private void setGridDimensions(Map<GeodesyPlanePos, Boolean> map) {
        geodesyAssert(!map.isEmpty());

        int minA = Integer.MAX_VALUE, minB = Integer.MAX_VALUE, maxA = Integer.MIN_VALUE, maxB = Integer.MIN_VALUE;
        for (var planePos : map.keySet()) {
            minA = min(minA, planePos.a());
            minB = min(minB, planePos.b());
            maxA = max(maxA, planePos.a());
            maxB = max(maxB, planePos.b());
        }

        // The -1 and +1 ensure we get an extra row and column in each direction
        minCoordA = minA - 1;
        minCoordB = minB - 1;
        maxCoordA = maxA + 1;
        maxCoordB = maxB + 1;
    }

    /**
     * Initializes the slice grid based on {@code map}.
     * For positions not in the map, a FreeCell is initialized, indicating the slice in that grid may either become
     * set or unset
     * For positions in the map, a SetCell is created if the boolean indicates that the slice must always be active,
     * and an unsetCell is created if it indicates that the slice may never be active.
     * @param map A mapping from geodesy plane positions (slices) to a boolean indicating whether they are active.
     */
    private void initGrid(Map<GeodesyPlanePos, Boolean> map) {
        geodesyAssert(!map.isEmpty());
        // NOTE: Currently, the 'view' one has of the grid is completely arbitrary.
        //       The grid can be flipped or rotated compared to the in-game version.
        //       A better approach to handling the coordinates is required to solve this.
        grid = new ArrayList<>();
        for (int a = 0; a < gridLenA; a++) {
            grid.add(new ArrayList<>());
            for (int b = 0; b < gridLenB; b++) {
                Boolean gridVal = map.getOrDefault(gridPosToPlanePos(a, b), null);
                Cell cell;
                if (gridVal == null) {
                    cell = new FreeCell(a, b);
                } else if (gridVal) {
                    cell = new SetCell(a, b);
                } else {
                    cell = new UnsetCell(a, b);
                }
                grid.get(a).add(cell);
            }
        }
    }

    private GeodesyPlanePos gridPosToPlanePos(int a, int b) {
        return new GeodesyPlanePos(plane, a + minCoordA, b + minCoordB);
    }

    private void forAllGridElements(Consumer<Cell> fun) {
        for (int a = 0; a < gridLenA; a++) {
            for (int b = 0; b < gridLenB; b++) {
                fun.accept(grid.get(a).get(b));
            }
        }
    }

    private Stream<Cell> gridStream() {
        return grid.stream().flatMap(Collection::stream);
    }

    private void printGrid(Function<Cell, Ansi> printFun) {
        var output = new StringBuilder();
        for (int a = 0; a < gridLenA; a++) {
            for (int b = 0; b < gridLenB; b++) {
                output.append(printFun.apply(grid.get(a).get(b)));
            }
            output.append("\n");
        }
        System.out.println(output);
    }

    public void printUnclustered() {
        printGrid(Cell::toAnsiUnclustered);
    }

}
