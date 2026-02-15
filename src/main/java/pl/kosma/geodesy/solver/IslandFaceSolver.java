package pl.kosma.geodesy.solver;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/*
 * Optimized face solver using island-based backtracking algorithm.
 *
 * Finds optimal placement of "islands" (connected groups of slime/honey blocks)
 * to cover harvest cells. Constraints:
 * - Each island must be 4-12 cells in size
 * - Each island must have an L-shape (required for flying machine mechanics)
 * - Adjacent islands must have different colors (slime vs honey)
 * - L-shapes of different islands cannot be adjacent
 * - Islands cannot overlap or cover blocked cells
 *
 * Maximizes: ones_covered - (island_count * island_cost)
 */
public class IslandFaceSolver implements FaceSolver {

    private static final Logger LOGGER = LoggerFactory.getLogger("IslandFaceSolver");

    private static final int MIN_ISLAND_SIZE = 4;
    private static final int MAX_ISLAND_SIZE = 12;
    private static final int MAX_SHAPES_PER_TARGET = 1000;

    // Grid state
    private byte[][] grid;
    private int rows;
    private int cols;
    private int totalCells;
    private double islandCost;
    private long timeoutMs;
    private long startTime;

    // Target tracking
    private List<int[]> targets;  // List of [row, col] for all 1s
    private Long2IntOpenHashMap targetIndices;  // Map cell key -> index in targets

    // Precomputed shapes: Map target_index -> list of Shape
    private Int2ObjectOpenHashMap<List<Shape>> possibleShapes;

    // Sorted target indices (by scarcity - fewest shapes first)
    private int[] sortedTargetIndices;

    // Best solution found
    private List<Island> bestSolution;
    private double maxScore;

    private static final int[][] DIRECTIONS = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

    private static class Shape {
        final BitSet mask;
        final int onesCovered;
        final LongOpenHashSet cells;

        Shape(BitSet mask, int onesCovered, LongOpenHashSet cells) {
            this.mask = mask;
            this.onesCovered = onesCovered;
            this.cells = cells;
        }
    }

    private static class Island {
        final LongOpenHashSet cells;
        final LongOpenHashSet lShapeCells;  // The 4 cells forming the L-shape
        final int material;  // 0 = slime, 1 = honey

        Island(LongOpenHashSet cells, LongOpenHashSet lShapeCells, int material) {
            this.cells = cells;
            this.lShapeCells = lShapeCells;
            this.material = material;
        }
    }

    @Override
    public SolverResult solve(FaceGrid input, SolverConfig config) {
        startTime = System.currentTimeMillis();
        timeoutMs = config.getTimeoutMs();
        islandCost = config.getCostThreshold();

        // Convert FaceGrid to internal grid format (row-major, matching FaceGrid's storage)
        rows = input.getHeight();
        cols = input.getWidth();
        totalCells = rows * cols;
        grid = new byte[rows][cols];

        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                grid[y][x] = input.getCell(x, y);
            }
        }

        // Initialize state
        targets = new ArrayList<>();
        targetIndices = new Long2IntOpenHashMap();
        targetIndices.defaultReturnValue(-1);
        possibleShapes = new Int2ObjectOpenHashMap<>();
        bestSolution = new ArrayList<>();
        maxScore = Double.NEGATIVE_INFINITY;

        // Find all target cells
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (grid[r][c] == FaceGrid.CELL_HARVEST) {
                    targetIndices.put(cellKey(r, c), targets.size());
                    targets.add(new int[]{r, c});
                }
            }
        }

        if (targets.isEmpty()) {
            LOGGER.info("No harvest cells to solve");
            return SolverResult.empty(input);
        }

        LOGGER.info("Solving {}x{} grid with {} harvest cells", cols, rows, targets.size());

        precomputeShapes();
        backtrack(0, new BitSet(totalCells), new ArrayList<>(), 0, 0);

        long solveTime = System.currentTimeMillis() - startTime;
        boolean timedOut = solveTime >= timeoutMs;

        return buildResult(input, solveTime, timedOut);
    }

    private long cellKey(int row, int col) {
        return ((long) row << 16) | (col & 0xFFFF);
    }

    private int keyRow(long key) {
        return (int) (key >> 16);
    }

    private int keyCol(long key) {
        return (int) (key & 0xFFFF);
    }

    private int cellBit(int row, int col) {
        return row * cols + col;
    }

    private BitSet getShapeMask(LongOpenHashSet cells) {
        BitSet mask = new BitSet(totalCells);
        for (long key : cells) {
            int bit = cellBit(keyRow(key), keyCol(key));
            mask.set(bit);
        }
        return mask;
    }

    // An L-shape requires at least one cell to have neighbors in perpendicular directions.
    private boolean hasLShape(LongOpenHashSet cells) {
        for (long key : cells) {
            int r = keyRow(key);
            int c = keyCol(key);

            // Check for horizontal line segment (3 cells)
            boolean hasLeft = cells.contains(cellKey(r, c - 1));
            boolean hasRight = cells.contains(cellKey(r, c + 1));

            if (hasLeft && hasRight) {
                for (int dr : new int[]{-1, 1}) {
                    for (int dc = -1; dc <= 1; dc++) {
                        if (cells.contains(cellKey(r + dr, c + dc))) {
                            return true;
                        }
                    }
                }
            }

            // Check for vertical line segment (3 cells)
            boolean hasUp = cells.contains(cellKey(r - 1, c));
            boolean hasDown = cells.contains(cellKey(r + 1, c));

            if (hasUp && hasDown) {
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc : new int[]{-1, 1}) {
                        if (cells.contains(cellKey(r + dr, c + dc))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private void precomputeShapes() {
        LOGGER.debug("Pre-computing shapes...");

        ObjectOpenHashSet<LongOpenHashSet> seenShapesGlobal = new ObjectOpenHashSet<>();

        for (int tIdx = 0; tIdx < targets.size(); tIdx++) {
            possibleShapes.put(tIdx, new ArrayList<>());

            int[] start = targets.get(tIdx);
            long startKey = cellKey(start[0], start[1]);

            // BFS to find shapes starting from this target
            // Use ArrayDeque as a deque: harvest neighbors go to front, air to back
            ArrayDeque<LongOpenHashSet> queue = new ArrayDeque<>();
            ObjectOpenHashSet<LongOpenHashSet> seenLocal = new ObjectOpenHashSet<>();

            LongOpenHashSet initial = new LongOpenHashSet();
            initial.add(startKey);
            queue.add(initial);
            seenLocal.add(initial);

            int shapesFound = 0;

            while (!queue.isEmpty() && shapesFound < MAX_SHAPES_PER_TARGET) {
                LongOpenHashSet current = queue.poll();

                if (current.size() < MAX_ISLAND_SIZE) {
                    LongOpenHashSet neighbors = new LongOpenHashSet();

                    for (long key : current) {
                        int r = keyRow(key);
                        int c = keyCol(key);

                        for (int[] dir : DIRECTIONS) {
                            int nr = r + dir[0];
                            int nc = c + dir[1];

                            if (nr >= 0 && nr < rows && nc >= 0 && nc < cols
                                    && grid[nr][nc] != FaceGrid.CELL_BLOCKED) {
                                long nkey = cellKey(nr, nc);
                                if (!current.contains(nkey)) {
                                    neighbors.add(nkey);
                                }
                            }
                        }
                    }

                    for (long n : neighbors) {
                        LongOpenHashSet newShape = new LongOpenHashSet(current);
                        newShape.add(n);

                        if (!seenLocal.contains(newShape)) {
                            seenLocal.add(newShape);

                            // Prioritize harvest-cell neighbors: add to front of queue
                            int nr = keyRow(n);
                            int nc = keyCol(n);
                            if (grid[nr][nc] == FaceGrid.CELL_HARVEST) {
                                queue.addFirst(newShape);
                            } else {
                                queue.addLast(newShape);
                            }

                            if (newShape.size() >= MIN_ISLAND_SIZE && hasLShape(newShape)) {
                                if (!seenShapesGlobal.contains(newShape)) {
                                    seenShapesGlobal.add(newShape);

                                    BitSet mask = getShapeMask(newShape);
                                    int ones = 0;
                                    for (long key : newShape) {
                                        int r = keyRow(key);
                                        int c = keyCol(key);
                                        if (grid[r][c] == FaceGrid.CELL_HARVEST) {
                                            ones++;
                                        }
                                    }

                                    Shape shape = new Shape(mask, ones, new LongOpenHashSet(newShape));

                                    // Assign shape to every target it covers
                                    for (long key : newShape) {
                                        int ti = targetIndices.get(key);
                                        if (ti != -1) {
                                            possibleShapes.computeIfAbsent(ti, k -> new ArrayList<>()).add(shape);
                                        }
                                    }

                                    shapesFound++;
                                }
                            }
                        }
                    }
                }
            }
        }

        // Sort targets by scarcity (fewest shapes first)
        sortedTargetIndices = new int[targets.size()];
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            indices.add(i);
        }
        indices.sort(Comparator.comparingInt(i -> possibleShapes.getOrDefault(i, Collections.emptyList()).size()));
        for (int i = 0; i < indices.size(); i++) {
            sortedTargetIndices[i] = indices.get(i);
        }

        LOGGER.debug("Found {} unique shapes", seenShapesGlobal.size());
    }

    private void backtrack(int sortedIdx, BitSet occupiedMask, List<Island> currentIslands,
                           int currentOnes, int currentIslandsCount) {
        if (System.currentTimeMillis() - startTime > timeoutMs) {
            return;
        }

        // Base case: all targets considered
        if (sortedIdx >= targets.size()) {
            double score = currentOnes - (currentIslandsCount * islandCost);
            if (score > maxScore) {
                maxScore = score;
                bestSolution = new ArrayList<>();
                for (Island island : currentIslands) {
                    bestSolution.add(new Island(
                            new LongOpenHashSet(island.cells),
                            new LongOpenHashSet(island.lShapeCells),
                            island.material));
                }
            }
            return;
        }

        int realTargetIdx = sortedTargetIndices[sortedIdx];
        int[] targetPos = targets.get(realTargetIdx);
        int targetBit = cellBit(targetPos[0], targetPos[1]);

        // Pruning: target already covered?
        if (occupiedMask.get(targetBit)) {
            backtrack(sortedIdx + 1, occupiedMask, currentIslands, currentOnes, currentIslandsCount);
            return;
        }

        // Pruning: score estimation
        int remainingTargets = 0;
        for (int i = sortedIdx; i < targets.size(); i++) {
            int ti = sortedTargetIndices[i];
            int[] tp = targets.get(ti);
            int bit = cellBit(tp[0], tp[1]);
            if (!occupiedMask.get(bit)) {
                remainingTargets++;
            }
        }

        double currentScore = currentOnes - (currentIslandsCount * islandCost);
        if (currentScore + remainingTargets <= maxScore) {
            return;
        }

        List<Shape> shapes = possibleShapes.getOrDefault(realTargetIdx, Collections.emptyList());
        shapes.sort((a, b) -> Integer.compare(b.onesCovered, a.onesCovered));

        for (Shape shape : shapes) {
            if (occupiedMask.intersects(shape.mask)) continue;

            LongOpenHashSet newLShape = findLShapeCells(shape.cells);
            if (newLShape == null) continue;

            Set<Integer> adjColors = new HashSet<>();
            boolean possible = true;

            for (Island existing : currentIslands) {
                // L-shapes contain the flying machine mechanism (pistons + slime/honey).
                // Even though adjacent islands use different materials, adjacent L-shapes
                // would cause mechanical interference during piston extension — the
                // flying machines would push/pull each other's components.
                if (isAdjacent(newLShape, existing.lShapeCells)) {
                    possible = false;
                    break;
                }

                // Adjacent islands must have different colors
                if (isAdjacent(shape.cells, existing.cells)) {
                    adjColors.add(existing.material);
                    if (adjColors.size() >= 2) {
                        possible = false;
                        break;
                    }
                }
            }

            if (!possible) continue;

            int color = adjColors.contains(0) ? 1 : 0;

            currentIslands.add(new Island(shape.cells, newLShape, color));

            BitSet newOccupied = (BitSet) occupiedMask.clone();
            newOccupied.or(shape.mask);

            backtrack(
                    sortedIdx + 1,
                    newOccupied,
                    currentIslands,
                    currentOnes + shape.onesCovered,
                    currentIslandsCount + 1
            );

            currentIslands.remove(currentIslands.size() - 1);
        }

        // Option: skip this target
        backtrack(sortedIdx + 1, occupiedMask, currentIslands, currentOnes, currentIslandsCount);
    }

    private boolean isAdjacent(LongOpenHashSet cells1, LongOpenHashSet cells2) {
        for (long key : cells1) {
            int r = keyRow(key);
            int c = keyCol(key);
            for (int[] dir : DIRECTIONS) {
                if (cells2.contains(cellKey(r + dir[0], c + dir[1]))) {
                    return true;
                }
            }
        }
        return false;
    }

    // Finds the L-shape cells (4 cells: 3 in a row + 1 perpendicular).
    private LongOpenHashSet findLShapeCells(LongOpenHashSet cells) {
        for (long key : cells) {
            int x = keyRow(key);
            int y = keyCol(key);

            for (int[] stemDir : DIRECTIONS) {
                int prevX = x - stemDir[0];
                int prevY = y - stemDir[1];
                int nextX = x + stemDir[0];
                int nextY = y + stemDir[1];

                long prevKey = cellKey(prevX, prevY);
                long nextKey = cellKey(nextX, nextY);

                if (cells.contains(prevKey) && cells.contains(nextKey)) {
                    for (int[] perpDir : DIRECTIONS) {
                        if (perpDir[0] == stemDir[0] && perpDir[1] == stemDir[1]) continue;
                        if (perpDir[0] == -stemDir[0] && perpDir[1] == -stemDir[1]) continue;

                        // Check corner at prev end
                        int cornerX = prevX + perpDir[0];
                        int cornerY = prevY + perpDir[1];
                        long cornerKey = cellKey(cornerX, cornerY);

                        if (cells.contains(cornerKey)) {
                            LongOpenHashSet lShape = new LongOpenHashSet();
                            lShape.add(prevKey);
                            lShape.add(key);
                            lShape.add(nextKey);
                            lShape.add(cornerKey);
                            return lShape;
                        }

                        // Check corner at next end
                        cornerX = nextX + perpDir[0];
                        cornerY = nextY + perpDir[1];
                        cornerKey = cellKey(cornerX, cornerY);

                        if (cells.contains(cornerKey)) {
                            LongOpenHashSet lShape = new LongOpenHashSet();
                            lShape.add(prevKey);
                            lShape.add(key);
                            lShape.add(nextKey);
                            lShape.add(cornerKey);
                            return lShape;
                        }
                    }
                }
            }
        }
        return null;
    }

    private SolverResult buildResult(FaceGrid input, long solveTime, boolean timedOut) {
        SolverResult.Builder builder = SolverResult.builder(input.getWidth(), input.getHeight(), input.getDirection())
                .totalHarvest(input.getHarvestCount())
                .solveTimeMs(solveTime)
                .timedOut(timedOut);

        LongOpenHashSet coveredCells = new LongOpenHashSet();
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
            SolverResult.PlacementType type = (island.material == 0)
                    ? SolverResult.PlacementType.SLIME
                    : SolverResult.PlacementType.HONEY;

            Set<int[]> resultCells = new HashSet<>();
            for (long key : island.cells) {
                int r = keyRow(key);
                int c = keyCol(key);
                // FaceGrid uses (x, y) where x=col, y=row
                builder.setPlacement(c, r, type);
                resultCells.add(new int[]{c, r});
            }

            // Convert internal L-shape cells to [x, y] coordinates for SolverResult
            Set<int[]> resultLShapeCells = new HashSet<>();
            for (long key : island.lShapeCells) {
                int r = keyRow(key);
                int c = keyCol(key);
                resultLShapeCells.add(new int[]{c, r});
            }

            builder.addIsland(new SolverResult.Island(resultCells, resultLShapeCells, type));
        }

        LOGGER.info("Solution: {} islands, {} harvest covered", bestSolution.size(), harvestCovered);

        return builder.build();
    }
}
