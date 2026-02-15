package pl.kosma.geodesy.solver;

import net.minecraft.util.math.Direction;

/**
 * Represents a 2D grid of a single face of the geode projection.
 * This is the input to the solver algorithm.
 *
 * Cell values: 0 = air, -1 = blocked (crying obsidian), 1 = harvest (pumpkin)
 *
 * Internal storage is row-major: cells[row][col] (i.e. cells[y][x]).
 */
public class FaceGrid {

    public static final byte CELL_AIR = 0;
    public static final byte CELL_BLOCKED = -1;
    public static final byte CELL_HARVEST = 1;

    private final int width;
    private final int height;
    private final byte[][] cells;  // row-major: cells[y][x]
    private final Direction direction;

    public FaceGrid(int width, int height, Direction direction) {
        this.width = width;
        this.height = height;
        this.direction = direction;
        this.cells = new byte[height][width];
    }

    public FaceGrid(byte[][] cells, int width, int height, Direction direction) {
        this.width = width;
        this.height = height;
        this.direction = direction;
        this.cells = new byte[height][width];
        for (int y = 0; y < height; y++) {
            System.arraycopy(cells[y], 0, this.cells[y], 0, width);
        }
    }

    public byte getCell(int x, int y) {
        return cells[y][x];
    }

    public void setCell(int x, int y, byte value) {
        cells[y][x] = value;
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

    public int countCells(byte value) {
        int count = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (cells[y][x] == value) {
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

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("FaceGrid[").append(width).append("x").append(height)
          .append(", direction=").append(direction).append("]\n");
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                char c = switch (cells[y][x]) {
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
