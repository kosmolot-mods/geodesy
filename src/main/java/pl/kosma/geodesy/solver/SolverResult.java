package pl.kosma.geodesy.solver;

import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Represents the result of solving a single face.
 * Contains placement instructions (NONE, SLIME, HONEY) for each cell.
 */
public class SolverResult {

    public enum PlacementType {
        NONE,
        SLIME,
        HONEY
    }

    /**
     * Represents an island (connected group of cells) in the solution.
     */
    public static class Island {
        private final Set<int[]> cells;  // Set of [x, y] coordinates
        private final PlacementType material;

        public Island(Set<int[]> cells, PlacementType material) {
            this.cells = cells;
            this.material = material;
        }

        public Set<int[]> getCells() {
            return cells;
        }

        public PlacementType getMaterial() {
            return material;
        }
    }

    private final int width;
    private final int height;
    private final Direction direction;
    private final PlacementType[][] placements;
    private final List<Island> islands;
    private final int harvestCovered;
    private final int totalHarvest;
    private final long solveTimeMs;
    private final boolean timedOut;

    private SolverResult(Builder builder) {
        this.width = builder.width;
        this.height = builder.height;
        this.direction = builder.direction;
        this.placements = builder.placements;
        this.islands = new ArrayList<>(builder.islands);
        this.harvestCovered = builder.harvestCovered;
        this.totalHarvest = builder.totalHarvest;
        this.solveTimeMs = builder.solveTimeMs;
        this.timedOut = builder.timedOut;
    }

    public PlacementType getPlacement(int x, int y) {
        return placements[x][y];
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public Direction getDirection() {
        return direction;
    }

    public List<Island> getIslands() {
        return Collections.unmodifiableList(islands);
    }

    public int getHarvestCovered() {
        return harvestCovered;
    }

    public int getTotalHarvest() {
        return totalHarvest;
    }

    public float getCoveragePercent() {
        if (totalHarvest == 0) return 100.0f;
        return 100.0f * harvestCovered / totalHarvest;
    }

    public long getSolveTimeMs() {
        return solveTimeMs;
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    public int getBlockCount() {
        int count = 0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (placements[x][y] != PlacementType.NONE) {
                    count++;
                }
            }
        }
        return count;
    }

    public static Builder builder(int width, int height, Direction direction) {
        return new Builder(width, height, direction);
    }

    public static SolverResult empty(FaceGrid input) {
        return builder(input.getWidth(), input.getHeight(), input.getDirection())
                .totalHarvest(input.getHarvestCount())
                .harvestCovered(0)
                .solveTimeMs(0)
                .build();
    }

    @Override
    public String toString() {
        return String.format("SolverResult[%dx%d, direction=%s, coverage=%.1f%% (%d/%d), blocks=%d, time=%dms%s]",
                width, height, direction, getCoveragePercent(), harvestCovered, totalHarvest,
                getBlockCount(), solveTimeMs, timedOut ? ", TIMED OUT" : "");
    }

    public static class Builder {
        private final int width;
        private final int height;
        private final Direction direction;
        private final PlacementType[][] placements;
        private final List<Island> islands = new ArrayList<>();
        private int harvestCovered = 0;
        private int totalHarvest = 0;
        private long solveTimeMs = 0;
        private boolean timedOut = false;

        private Builder(int width, int height, Direction direction) {
            this.width = width;
            this.height = height;
            this.direction = direction;
            this.placements = new PlacementType[width][height];
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    placements[x][y] = PlacementType.NONE;
                }
            }
        }

        public Builder setPlacement(int x, int y, PlacementType type) {
            placements[x][y] = type;
            return this;
        }

        public Builder addIsland(Island island) {
            islands.add(island);
            return this;
        }

        public Builder harvestCovered(int count) {
            this.harvestCovered = count;
            return this;
        }

        public Builder totalHarvest(int count) {
            this.totalHarvest = count;
            return this;
        }

        public Builder solveTimeMs(long timeMs) {
            this.solveTimeMs = timeMs;
            return this;
        }

        public Builder timedOut(boolean timedOut) {
            this.timedOut = timedOut;
            return this;
        }

        public SolverResult build() {
            return new SolverResult(this);
        }
    }
}
