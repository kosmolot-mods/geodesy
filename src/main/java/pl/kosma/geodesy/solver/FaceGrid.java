package pl.kosma.geodesy.solver;

import net.minecraft.util.math.Direction;

/**
 * Represents a 2D grid of a single face of the geode projection.
 * This is the input to the solver algorithm.
 *
 * Cell values: 0 = air, -1 = blocked (crying obsidian), 1 = harvest (pumpkin)
 */
public class FaceGrid {

    public static final int CELL_AIR = 0;
    public static final int CELL_BLOCKED = -1;
    public static final int CELL_HARVEST = 1;

    private final int width;
    private final int height;
    private final int[][] cells;
    private final Direction direction;

    public FaceGrid(int width, int height, Direction direction) {
        this.width = width;
        this.height = height;
        this.direction = direction;
        this.cells = new int[width][height];
    }

    public FaceGrid(int[][] cells, Direction direction) {
        this.width = cells.length;
        this.height = cells.length > 0 ? cells[0].length : 0;
        this.direction = direction;
        this.cells = new int[width][height];
        for (int x = 0; x < width; x++) {
            System.arraycopy(cells[x], 0, this.cells[x], 0, height);
        }
    }

    public int getCell(int x, int y) {
        return cells[x][y];
    }

    public void setCell(int x, int y, int value) {
        cells[x][y] = value;
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

    public int countCells(int value) {
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
        return new FaceGrid(cells, direction);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("FaceGrid[").append(width).append("x").append(height)
          .append(", direction=").append(direction).append("]\n");
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
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
