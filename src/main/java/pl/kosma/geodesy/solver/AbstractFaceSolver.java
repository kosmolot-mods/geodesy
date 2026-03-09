package pl.kosma.geodesy.solver;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;
import java.util.List;

public abstract class AbstractFaceSolver implements FaceSolver {

    public static final Logger LOGGER = LoggerFactory.getLogger("AbstractFaceSolver");

    public static final int MIN_ISLAND_SIZE = 4;
    public static final int MAX_ISLAND_SIZE = 12;

    public static final byte SLIME = 1;
    public static final byte HONEY = 2;

    // Grid state
    protected final byte[][] grid;
    protected final int rows;
    protected final int cols;
    protected final int totalCells;
    protected final double islandCost;
    protected final long timeoutMs;
    protected long startTime;

    public AbstractFaceSolver(FaceGrid input, SolverConfig config) {
        grid = input.copyCells();
        rows = input.width();
        cols = input.height();
        totalCells = rows * cols;

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

        IntSet coveredCells = new IntOpenHashSet();
        for (Island island : bestSolution) {
            coveredCells.addAll(island.cells);
        }

        int harvestCovered = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (grid[r][c] == FaceGrid.CELL_HARVEST && coveredCells.contains(cellKey(r, c))) {
                    harvestCovered++;
                }
            }
        }
        builder.harvestCovered(harvestCovered);

        for (Island island : bestSolution) {
            for (int key : island.cells) {
                int r = keyRow(key);
                int c = keyCol(key);
                // FaceGrid uses (x, y) where x=row, y=col
                builder.setPlacement(r, c, island.material);
            }

            builder.addIsland(island);
        }

        LOGGER.info("Solution: {} islands, {} harvest covered", bestSolution.size(), harvestCovered);

        return builder.build();
    }

    /**
     * @param material    1 = slime, 2 = honey
     */
    public record Island(IntSet cells, BitSet mask, FlyingMachine flyingMachine, byte material) {}

    /**
     * @param stemCells         the 3 cells forming the main flying machine
     * @param stemMask          pre-computed mask of the stem cells, used for quick intersection checks
     * @param stemNeighborsMask pre-computed mask of all neighbors of the stem cells, used for quick adjacency checks
     * @param stopperCell       the neighbor cell for the blocker block
     */
    public record FlyingMachine(IntSet stemCells, BitSet stemMask, BitSet stemNeighborsMask, int stopperCell) {}
}
