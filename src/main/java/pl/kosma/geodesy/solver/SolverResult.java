package pl.kosma.geodesy.solver;

import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents the result of solving a single face.
 * Contains placement instructions (NONE, SLIME, HONEY) for each cell.
 */
public record SolverResult(int width, int height, Direction direction,
                           byte[][] placements, List<BacktrackingFaceSolver.Island> islands,
                           int harvestCovered, int totalHarvest, long solveTimeMs, boolean timedOut) {

    private SolverResult(Builder builder) {
        this(builder.width, builder.height, builder.direction, builder.placements, Collections.unmodifiableList(builder.islands), builder.harvestCovered, builder.totalHarvest, builder.solveTimeMs, builder.timedOut);
    }

    public byte getPlacement(int x, int y) {
        return placements[x][y];
    }

    public float getCoveragePercent() {
        if (totalHarvest == 0) return 100.0f;
        return 100.0f * harvestCovered / totalHarvest;
    }

    public int getBlockCount() {
        int count = 0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (placements[x][y] != 0) {
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
        return builder(input.width(), input.height(), input.direction())
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
        private final byte[][] placements;
        private final List<BacktrackingFaceSolver.Island> islands = new ArrayList<>();
        private int harvestCovered = 0;
        private int totalHarvest = 0;
        private long solveTimeMs = 0;
        private boolean timedOut = false;

        private Builder(int width, int height, Direction direction) {
            this.width = width;
            this.height = height;
            this.direction = direction;
            this.placements = new byte[width][height];
        }

        public Builder setPlacement(int x, int y, byte type) {
            placements[x][y] = type;
            return this;
        }

        public Builder addIsland(BacktrackingFaceSolver.Island island) {
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
