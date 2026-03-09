package pl.kosma.geodesy.solver;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;

import java.util.BitSet;
import java.util.List;

public abstract class AbstractFaceSolver implements FaceSolver {

    public static final int MIN_ISLAND_SIZE = 4;
    public static final int MAX_ISLAND_SIZE = 12;

    public static final byte SLIME = 1;
    public static final byte HONEY = 2;

    // Grid state
    protected final byte[][] grid;
    protected final int rows;
    protected final int cols;
    protected final double islandCost;
    protected final long timeoutMs;
    protected long startTime;

    public AbstractFaceSolver(FaceGrid input, SolverConfig config) {
        grid = input.copyCells();
        rows = input.width();
        cols = input.height();

        timeoutMs = config.getTimeoutMs();
        islandCost = config.getCostThreshold();
    }

    public static int cellKey(int row, int col) {
        return (row << 16) | (col & 0xFFFF);
    }

    public static int keyRow(int key) {
        return key >> 16;
    }

    public static int keyCol(int key) {
        // This interprets the 16th bit as the sign bit
        return key << 16 >> 16;
    }

    public int cellBit(int row, int col) {
        return row * cols + col;
    }

    protected SolverResult buildResult(FaceGrid input, List<Island> bestSolution, long solveTime, boolean timedOut) {
        SolverResult.Builder builder = SolverResult.builder(input.width(), input.height(), input.direction())
                .totalHarvest(input.getHarvestCount())
                .solveTimeMs(solveTime)
                .timedOut(timedOut);

        builder.harvestCovered((int) bestSolution.stream()
                .map(Island::cells)
                .flatMapToInt(IntSet::intStream)
                .distinct()
                .filter(key -> grid[keyRow(key)][keyCol(key)] == FaceGrid.CELL_HARVEST)
                .count());

        for (Island island : bestSolution) {
            for (int key : island.cells) {
                int r = keyRow(key);
                int c = keyCol(key);
                // FaceGrid uses (x, y) where x=row, y=col
                builder.setPlacement(r, c, island.material);
            }

            builder.addIsland(island);
        }

        return builder.build();
    }

    /**
     * @param material    1 = slime, 2 = honey
     */
    public record Island(IntSet cells, BitSet mask, FlyingMachine flyingMachine, byte material) {
        public Island withCell(int cell, int bit) {
            IntSet newCells = new IntOpenHashSet(cells);
            newCells.add(cell);

            BitSet newMask = (BitSet) mask.clone();
            newMask.set(bit);

            return new Island(IntSets.unmodifiable(newCells), newMask, flyingMachine, material);
        }

        public Island union(Island other) {
            IntSet newCells = new IntOpenHashSet(cells);
            newCells.addAll(other.cells);

            BitSet newMask = (BitSet) mask.clone();
            newMask.or(other.mask);

            return new Island(IntSets.unmodifiable(newCells), newMask, flyingMachine, material);
        }
    }

    /**
     * @param stemCells         the 3 cells forming the main flying machine
     * @param stemMask          pre-computed mask of the stem cells, used for quick intersection checks
     * @param stemNeighborsMask pre-computed mask of all neighbors of the stem cells, used for quick adjacency checks
     * @param stopperCell       the neighbor cell for the blocker block
     */
    public record FlyingMachine(IntSet stemCells, BitSet stemMask, BitSet stemNeighborsMask, int stopperCell) {}
}
