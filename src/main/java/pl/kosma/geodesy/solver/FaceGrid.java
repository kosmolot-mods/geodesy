package pl.kosma.geodesy.solver;

import net.minecraft.util.math.Direction;

/**
 * Represents a 2D grid of a single face of the geode projection.
 * This is the input to the solver algorithm.
 *
 * <p>Cell values: 0 = air, -1 = blocked (crying obsidian), 1 = harvest (pumpkin)
 *
 * <p>Internal storage is row-major: cells[row][col] (i.e. cells[x][y]).
 */
public record FaceGrid(int width, int height, byte[][] cells, Direction direction) {
    public static final byte CELL_AIR = 0;
    public static final byte CELL_BLOCKED = -1;
    public static final byte CELL_HARVEST = 1;

    public FaceGrid(int width, int height, Direction direction) {
        this(width, height, new byte[width][height], direction);
    }

    public FaceGrid(byte[][] cells, int width, int height, Direction direction) {
        this(width, height, new byte[width][height], direction);
        for (int x = 0; x < width; x++) {
            System.arraycopy(cells[x], 0, this.cells[x], 0, height);
        }
    }

    public byte getCell(int x, int y) {
        return cells[x][y];
    }

    public void setCell(int x, int y, byte value) {
        cells[x][y] = value;
    }

    public int countCells(byte value) {
        int count = 0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (cells[x][y] == value) {
                    count++;
                }
            }
        }
        return count;
    }

    public int getHarvestCount() {
        return countCells(CELL_HARVEST);
    }

    public int getBlockedCount() {
        return countCells(CELL_BLOCKED);
    }

    public FaceGrid copy() {
        return new FaceGrid(cells, width, height, direction);
    }

    public byte[][] copyCells() {
        byte[][] copy = new byte[width][height];
        for (int x = 0; x < width; x++) {
            System.arraycopy(cells[x], 0, copy[x], 0, height);
        }
        return copy;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("FaceGrid[").append(width).append("x").append(height)
          .append(", direction=").append(direction).append("]\n");
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                char c = switch (cells[x][y]) {
                    case CELL_AIR -> '.';
                    case CELL_BLOCKED -> '#';
                    case CELL_HARVEST -> 'P';
                    default -> '?';
                };
                sb.append(c);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
