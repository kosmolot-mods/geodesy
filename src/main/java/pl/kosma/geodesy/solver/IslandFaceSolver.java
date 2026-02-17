package pl.kosma.geodesy.solver;

import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
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

    public static final byte SLIME = 1;
    public static final byte HONEY = 2;

    private static final Comparator<Shape> SHAPE_PRIORITY_COMPARATOR = Comparator.comparingInt(Shape::onesCovered).reversed();

    // Grid state
    private byte[][] grid;
    private int rows;
    private int cols;
    private int totalCells;
    private double islandCost;
    private long timeoutMs;
    private long startTime;

    // Target tracking
    private IntList targets;  // List of [row, col] for all 1s
    private Int2IntOpenHashMap targetIndices;  // Map cell key -> index in targets

    // Precomputed shapes: Map target_index -> list of Shape
    private Int2ObjectOpenHashMap<List<Shape>> possibleShapes;

    // Sorted target indices (by scarcity - fewest shapes first)
    private int[] sortedTargetIndices;

    // Best solution found
    private List<Island> bestSolution;
    private double maxScore;

    // Special processing for subtracting 1 from the lower 16 bits.
    // Underflow will impact the upper 16 bits, but we rely on range checks on the lower 16 bits to catch that.
    private static final int[] DIRECTIONS = {cellKey(0, 1), -1, cellKey(1, 0), cellKey(-1, 0)};

    private record Shape(IntSet cells, BitSet mask, BitSet neighborsMask, int onesCovered, LShape lShape) {}

    /**
     * @param material    1 = slime, 2 = honey
     */
    public record Island(IntSet cells, LShape lShape, byte material) {}

    /**
     * @param stemCells   the 3 cells forming the main stem of the L-shape
     * @param stopperCell the cell on the shorter leg of the L-shape
     */
    public record LShape(IntSet stemCells, BitSet stemMask, BitSet stemNeighborsMask, int stopperCell) {}

    @Override
    public SolverResult solve(FaceGrid input, SolverConfig config) {
        startTime = System.currentTimeMillis();
        timeoutMs = config.getTimeoutMs();
        islandCost = config.getCostThreshold();

        rows = input.width();
        cols = input.height();
        totalCells = rows * cols;
        grid = input.copyCells();

        // Initialize state
        targets = new IntArrayList();
        targetIndices = new Int2IntOpenHashMap();
        targetIndices.defaultReturnValue(-1);
        possibleShapes = new Int2ObjectOpenHashMap<>();
        bestSolution = new ArrayList<>();
        maxScore = Double.NEGATIVE_INFINITY;

        // Find all target cells
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (grid[r][c] == FaceGrid.CELL_HARVEST) {
                    int key = cellKey(r, c);
                    targetIndices.put(key, targets.size());
                    targets.add(key);
                }
            }
        }

        if (targets.isEmpty()) {
            LOGGER.info("No harvest cells to solve");
            return SolverResult.empty(input);
        }

        LOGGER.info("Solving {}x{} grid with {} harvest cells", rows, cols, targets.size());

        precomputeShapes();
        sortShapes();
        backtrack(0, new ArrayList<>(), new BitSet(totalCells), new BitSet(totalCells), new BitSet(totalCells), 0, targets.size(), 0);

        long solveTime = System.currentTimeMillis() - startTime;
        boolean timedOut = solveTime >= timeoutMs;

        return buildResult(input, solveTime, timedOut);
    }

    private static int cellKey(int row, int col) {
        return (row << 16) | (col & 0xFFFF);
    }

    public static int keyRow(int key) {
        return key >> 16;
    }

    public static int keyCol(int key) {
        // This interprets the 16th bit as the sign bit
        return key << 16 >> 16;
    }

    private int cellBit(int row, int col) {
        return row * cols + col;
    }

    private void precomputeShapes() {
        LOGGER.debug("Pre-computing shapes...");

        ObjectSet<IntSet> seenShapesGlobal = new ObjectOpenHashSet<>();

        for (int tIdx = 0; tIdx < targets.size(); tIdx++) {
            possibleShapes.computeIfAbsent(tIdx, k -> new ArrayList<>());

            int start = targets.getInt(tIdx);

            // BFS to find shapes starting from this target
            ArrayDeque<IntSet> queueHarvest = new ArrayDeque<>();
            ArrayDeque<IntSet> queueAir = new ArrayDeque<>();
            ObjectSet<IntSet> seenLocal = new ObjectOpenHashSet<>();

            IntSet initial = new IntOpenHashSet();
            initial.add(start);
            queueHarvest.add(initial);
            seenLocal.add(initial);

            int shapesFound = 0;

            while ((!queueHarvest.isEmpty() || !queueAir.isEmpty()) && shapesFound < MAX_SHAPES_PER_TARGET) {
                IntSet current = !queueHarvest.isEmpty() ? queueHarvest.poll() : queueAir.poll();

                if (current.size() >= MAX_ISLAND_SIZE) continue;
                IntSet neighbors = getNeighbors(current);

                for (int n : neighbors) {
                    IntSet newShape = new IntOpenHashSet(current);
                    newShape.add(n);

                    if (!seenLocal.add(newShape)) continue;

                    // We have not seen this shape locally
                    // Prioritize harvest-cell neighbors
                    int nr = keyRow(n);
                    int nc = keyCol(n);
                    if (grid[nr][nc] == FaceGrid.CELL_HARVEST) {
                        queueHarvest.add(newShape);
                    } else {
                        queueAir.add(newShape);
                    }

                    if (newShape.size() < MIN_ISLAND_SIZE) continue;

                    LShape lShape = findLShapeCells(newShape);
                    if (lShape == null) continue;

                    if (!seenShapesGlobal.add(newShape)) continue;

                    // We have not seen this shape globally
                    Shape shape = createShape(newShape, lShape);

                    // Assign shape to every target it covers
                    for (int key : newShape) {
                        int ti = targetIndices.get(key);
                        if (ti != -1) {
                            possibleShapes.computeIfAbsent(ti, k -> new ArrayList<>()).add(shape);
                        }
                    }

                    shapesFound++;
                }
            }
        }

        LOGGER.debug("Found {} unique shapes", seenShapesGlobal.size());
    }

    private IntSet getNeighbors(IntSet current) {
        IntSet neighbors = new IntOpenHashSet();

        for (int key : current) {
            for (int dir : DIRECTIONS) {
                int nkey = key + dir;
                int nr = keyRow(nkey);
                int nc = keyCol(nkey);

                if (nr >= 0 && nr < rows && nc >= 0 && nc < cols && grid[nr][nc] != FaceGrid.CELL_BLOCKED) {
                    neighbors.add(nkey);
                }
            }
        }
        return neighbors;
    }

    // Finds the L-shape cells (4 cells: 3 in a row + 1 perpendicular).
    private LShape findLShapeCells(IntSet cells) {
        for (int key : cells) {
            for (int stemDir : DIRECTIONS) {
                int prevKey = key - stemDir;
                int nextKey = key + stemDir;

                if (cells.contains(prevKey) && cells.contains(nextKey)) {
                    for (int perpDir : DIRECTIONS) {
                        // Black magic with packed shorts
                        if (perpDir == stemDir || perpDir == -stemDir) continue;

                        // Check corner at prev end
                        int cornerKey = prevKey + perpDir;
                        if (cells.contains(cornerKey)) {
                            IntSet stemCells = IntSet.of(prevKey, key, nextKey);
                            return new LShape(stemCells, getMask(stemCells), getNeighborsMask(stemCells), cornerKey);
                        }

                        // Check corner at next end
                        cornerKey = nextKey + perpDir;
                        if (cells.contains(cornerKey)) {
                            IntSet stemCells = IntSet.of(prevKey, key, nextKey);
                            return new LShape(stemCells, getMask(stemCells), getNeighborsMask(stemCells), cornerKey);
                        }
                    }
                }
            }
        }
        return null;
    }

    private Shape createShape(IntSet newShape, LShape lShape) {
        int ones = 0;

        for (int key : newShape) {
            int r = keyRow(key);
            int c = keyCol(key);
            if (grid[r][c] == FaceGrid.CELL_HARVEST) {
                ones++;
            }
        }

        return new Shape(newShape, getMask(newShape), getNeighborsMask(newShape), ones, lShape);
    }

    private BitSet getMask(IntSet cells) {
        BitSet mask = new BitSet();
        for (int key : cells) {
            int r = keyRow(key);
            int c = keyCol(key);
            mask.set(cellBit(r, c));
        }
        return mask;
    }

    private BitSet getNeighborsMask(IntSet shape) {
        BitSet neighborsMask = new BitSet();
        for (int key : shape) {
            int r = keyRow(key);
            int c = keyCol(key);
            for (int dir : DIRECTIONS) {
                int nr = r + keyRow(dir);
                int nc = c + keyCol(dir);
                if (nr >= 0 && nr < rows && nc >= 0 && nc < cols) {
                    neighborsMask.set(cellBit(nr, nc));
                }
            }
        }
        return neighborsMask;
    }

    private void sortShapes() {
        sortedTargetIndices = new int[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            sortedTargetIndices[i] = i;
        }
        IntArrays.quickSort(sortedTargetIndices, IntComparator.comparingInt(i -> possibleShapes.getOrDefault(i, Collections.emptyList()).size()));

        for (List<Shape> possibleShapes : possibleShapes.values()) {
            possibleShapes.sort(SHAPE_PRIORITY_COMPARATOR);
        }
    }

    private void backtrack(int sortedIdx, List<Island> currentIslands,
                           BitSet slimeMask, BitSet honeyMask, BitSet lShapeStemMask,
                           int currentOnes, int remainingPossibleTargets, int currentIslandsCount) {
        if (System.currentTimeMillis() - startTime > timeoutMs) {
            return;
        }

        double currentScore = currentOnes - (currentIslandsCount * islandCost);

        // Base case: all targets considered
        if (sortedIdx >= targets.size()) {
            if (currentScore > maxScore) {
                maxScore = currentScore;
                bestSolution = new ArrayList<>(currentIslands);
            }
            return;
        }

        // Pruning: score estimation
        if (currentScore + remainingPossibleTargets <= maxScore) return;

        int realTargetIdx = sortedTargetIndices[sortedIdx];
        int targetKey = targets.getInt(realTargetIdx);
        int targetBit = cellBit(keyRow(targetKey), keyCol(targetKey));

        // Pruning: target already covered?
        if (slimeMask.get(targetBit) || honeyMask.get(targetBit)) {
            backtrack(sortedIdx + 1, currentIslands, slimeMask, honeyMask, lShapeStemMask, currentOnes, remainingPossibleTargets, currentIslandsCount);
            return;
        }

        List<Shape> shapes = possibleShapes.getOrDefault(realTargetIdx, Collections.emptyList());

        for (Shape shape : shapes) {
            if (slimeMask.intersects(shape.mask) || honeyMask.intersects(shape.mask)) continue;

            // L-shapes contain the flying machine mechanism (pistons + slime/honey).
            // Even though adjacent islands use different materials, adjacent L-shapes
            // would cause mechanical interference during piston extension — the
            // flying machines would push/pull each other's components.
            if (isAdjacent(lShapeStemMask, shape.lShape)) continue;

            // Check if this shape would be adjacent to both slime and honey islands, which is not allowed
            boolean slimeAdj = isAdjacent(slimeMask, shape);
            if (slimeAdj && isAdjacent(honeyMask, shape)) continue;

            byte color = slimeAdj ? HONEY : SLIME;

            currentIslands.add(new Island(shape.cells, shape.lShape, color));
            if (slimeAdj) honeyMask.or(shape.mask);
            else slimeMask.or(shape.mask);
            lShapeStemMask.or(shape.lShape.stemMask);

            backtrack(
                    sortedIdx + 1,
                    currentIslands,
                    slimeMask,
                    honeyMask,
                    lShapeStemMask,
                    currentOnes + shape.onesCovered,
                    remainingPossibleTargets - shape.onesCovered,
                    currentIslandsCount + 1
            );

            currentIslands.removeLast();
            if (slimeAdj) honeyMask.andNot(shape.mask);
            else slimeMask.andNot(shape.mask);
            lShapeStemMask.andNot(shape.lShape.stemMask);
        }

        // Option: skip this target
        // There are no valid shapes that cover this target
        backtrack(sortedIdx + 1, currentIslands, slimeMask, honeyMask, lShapeStemMask, currentOnes, remainingPossibleTargets - 1, currentIslandsCount);
    }

    private boolean hasColor(byte colors, byte material) {
        return (colors & (1 << material)) != 0;
    }

    private boolean isAdjacent(BitSet lShapeStemMask, LShape newLShape) {
        return lShapeStemMask.intersects(newLShape.stemNeighborsMask);
    }

    private boolean isAdjacent(BitSet cellsMask, Shape newShape) {
        return cellsMask.intersects(newShape.neighborsMask);
    }

    private SolverResult buildResult(FaceGrid input, long solveTime, boolean timedOut) {
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
}
