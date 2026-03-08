package pl.kosma.geodesy.solver;

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
}
