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
 * - Each island must have a 1x3 shape and one free neighbor (required for a flying machine)
 * - Adjacent islands must have different colors (slime vs honey)
 * - Flying machines of different islands cannot be adjacent
 * - Islands cannot overlap or cover blocked cells
 *
 * Maximizes: ones_covered - (island_count * island_cost)
 */
public class BacktrackingFaceSolver extends AbstractFaceSolver implements FaceSolver {

    private static final Logger LOGGER = LoggerFactory.getLogger("BacktrackingFaceSolver");

    private static final int MAX_SHAPES_PER_TARGET = 100;

    private static final Comparator<Shape> SHAPE_PRIORITY_COMPARATOR = Comparator.comparingInt(Shape::onesCovered).reversed();

    // Target tracking
    private final IntList targets = new IntArrayList();  // List of [row, col] for all 1s
    private final Int2IntOpenHashMap targetIndices = new Int2IntOpenHashMap();  // Map cell key -> index in targets

    // Precomputed shapes: Map target_index -> list of Shape
    private final Int2ObjectOpenHashMap<List<Shape>> possibleShapes = new Int2ObjectOpenHashMap<>();

    // Sorted target indices (by scarcity - fewest shapes first)
    private int[] sortedTargetIndices;

    // Best solution found
    private double bestScore = Double.NEGATIVE_INFINITY;
    private List<Island> bestSolution = new ArrayList<>();
    // Store slime and honey masks for best solution for use in hill climbing
    private BitSet bestSolutionSlimeMask = new BitSet();
    private BitSet bestSolutionHoneyMask = new BitSet();

    // Time tracking
    private long backtrackCalls;
    private boolean timedOut;

    // Special processing for subtracting 1 from the lower 16 bits.
    // Underflow will impact the upper 16 bits, but we rely on range checks on the lower 16 bits to catch that.
    private static final int[] DIRECTIONS = {cellKey(0, 1), -1, cellKey(1, 0), cellKey(-1, 0)};

    private record Shape(IntSet cells, BitSet mask, BitSet neighborsMask, int onesCovered, FlyingMachine flyingMachine) {}

    public BacktrackingFaceSolver(FaceGrid input, SolverConfig config) {
        super(input, config);
    }

    @Override
    public SolverResult solve(FaceGrid input, SolverConfig config) {
        startTime = System.currentTimeMillis();

        int totalCells = rows * cols;

        // Initialize state
        targetIndices.defaultReturnValue(-1);

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
        hillClimbSolution();

        long solveTime = System.currentTimeMillis() - startTime;
        return buildResult(input, bestSolution, solveTime, timedOut);
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

            IntSet initial = IntSet.of(start);
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
                    newShape = IntSets.unmodifiable(newShape);

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

                    FlyingMachine flyingMachine = findFlyingMachine(newShape);
                    if (flyingMachine == null) continue;

                    if (!seenShapesGlobal.add(newShape)) continue;

                    // We have not seen this shape globally
                    Shape shape = createShape(newShape, flyingMachine);

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

                if (nr >= 0 && nr < rows && nc >= 0 && nc < cols && grid[nr][nc] != FaceGrid.CELL_BLOCKED && !current.contains(nkey)) {
                    neighbors.add(nkey);
                }
            }
        }
        return neighbors;
    }

    // Finds the flying machine cells (4 cells: 3 in a row + 1 neighbor).
    private FlyingMachine findFlyingMachine(IntSet cells) {
        for (int key : cells) {
            for (int i = 0; i <= 2; i += 2) {
                int stemDir = DIRECTIONS[i];
                int prevKey = key - stemDir;
                int nextKey = key + stemDir;

                if (cells.contains(prevKey) && cells.contains(nextKey)) {
                    // If stem is on +col (i=0), +row (perp=2) is perp
                    // If stem is on +row (i=2), +col (perp=0) is perp
                    int perpDir = DIRECTIONS[2 - i];
                    int target;

                    // 1. Check one side (perpDir)
                    if (cells.contains(target = prevKey + perpDir)
                        || cells.contains(target = key + perpDir)
                        || cells.contains(target = nextKey + perpDir))
                        return createFlyingMachine(prevKey, key, nextKey, target);

                    // 2. Check other side (-perpDir)
                    if (cells.contains(target = prevKey - perpDir)
                        || cells.contains(target = key - perpDir)
                        || cells.contains(target = nextKey - perpDir))
                        return createFlyingMachine(prevKey, key, nextKey, target);

                    // 3. Check end sides (1x4 case)
                    if (cells.contains(target = prevKey - stemDir)
                        || cells.contains(target = nextKey + stemDir))
                        return createFlyingMachine(prevKey, key, nextKey, target);
                }
            }
        }
        return null;
    }

    private FlyingMachine createFlyingMachine(int prevKey, int key, int nextKey, int target) {
        IntSet stemCells = IntSet.of(prevKey, key, nextKey);
        return new FlyingMachine(stemCells, getMask(stemCells), getNeighborsMask(stemCells), target);
    }

    private Shape createShape(IntSet newShape, FlyingMachine flyingMachine) {
        int ones = 0;

        for (int key : newShape) {
            int r = keyRow(key);
            int c = keyCol(key);
            if (grid[r][c] == FaceGrid.CELL_HARVEST) {
                ones++;
            }
        }

        return new Shape(newShape, getMask(newShape), getNeighborsMask(newShape), ones, flyingMachine);
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
                           BitSet slimeMask, BitSet honeyMask, BitSet flyingMachineStemMask,
                           int currentOnes, int remainingPossibleTargets, int currentIslandsCount) {
        if ((backtrackCalls++ & 0xFFFF) == 0 && System.currentTimeMillis() - startTime > timeoutMs) {
            timedOut = true;
            return;
        }

        double currentScore = currentOnes - (currentIslandsCount * islandCost);

        // Base case: all targets considered
        if (sortedIdx >= targets.size()) {
            if (currentScore > bestScore) {
                bestScore = currentScore;
                bestSolution = new ArrayList<>(currentIslands);
                bestSolutionSlimeMask = (BitSet) slimeMask.clone();
                bestSolutionHoneyMask = (BitSet) honeyMask.clone();
            }
            return;
        }

        // Pruning: score estimation
        if (currentScore + remainingPossibleTargets <= bestScore) return;

        int realTargetIdx = sortedTargetIndices[sortedIdx];
        int targetKey = targets.getInt(realTargetIdx);
        int targetBit = cellBit(keyRow(targetKey), keyCol(targetKey));

        // Pruning: target already covered?
        if (slimeMask.get(targetBit) || honeyMask.get(targetBit)) {
            backtrack(sortedIdx + 1, currentIslands, slimeMask, honeyMask, flyingMachineStemMask, currentOnes, remainingPossibleTargets, currentIslandsCount);
            return;
        }

        List<Shape> shapes = possibleShapes.getOrDefault(realTargetIdx, Collections.emptyList());

        for (Shape shape : shapes) {
            if (slimeMask.intersects(shape.mask) || honeyMask.intersects(shape.mask)) continue;

            // L-shapes contain the flying machine mechanism (pistons + slime/honey).
            // Even though adjacent islands use different materials, adjacent L-shapes
            // would cause mechanical interference during piston extension — the
            // flying machines would push/pull each other's components.
            if (isAdjacent(flyingMachineStemMask, shape.flyingMachine)) continue;

            // Check if this shape would be adjacent to both slime and honey islands, which is not allowed
            boolean slimeAdj = isAdjacent(slimeMask, shape);
            if (slimeAdj && isAdjacent(honeyMask, shape)) continue;

            byte color = slimeAdj ? HONEY : SLIME;

            currentIslands.add(new Island(shape.cells, shape.mask, shape.flyingMachine, color));
            if (slimeAdj) honeyMask.or(shape.mask);
            else slimeMask.or(shape.mask);
            flyingMachineStemMask.or(shape.flyingMachine.stemMask());

            backtrack(
                    sortedIdx + 1,
                    currentIslands,
                    slimeMask,
                    honeyMask,
                    flyingMachineStemMask,
                    currentOnes + shape.onesCovered,
                    remainingPossibleTargets - shape.onesCovered,
                    currentIslandsCount + 1
            );

            if (timedOut) return;

            currentIslands.removeLast();
            if (slimeAdj) honeyMask.andNot(shape.mask);
            else slimeMask.andNot(shape.mask);
            flyingMachineStemMask.andNot(shape.flyingMachine.stemMask());
        }

        // Option: skip this target
        // There are no valid shapes that cover this target
        backtrack(sortedIdx + 1, currentIslands, slimeMask, honeyMask, flyingMachineStemMask, currentOnes, remainingPossibleTargets - 1, currentIslandsCount);
    }

    private void hillClimbSolution() {
        boolean improved = true;
        while (improved) {
            improved = false;

            bestSolution.sort(Comparator.comparingInt(island -> island.cells().size()));

            for (int i = 0; i < bestSolution.size(); i++) {
                Island island = bestSolution.get(i);
                if (island.cells().size() >= MAX_ISLAND_SIZE) continue;

                IntSet neighbors = getNeighbors(island.cells());
                for (int n : neighbors) {
                    int nr = keyRow(n);
                    int nc = keyCol(n);
                    int nBit = cellBit(nr, nc);
                    BitSet materialMask = island.material() == SLIME ? bestSolutionSlimeMask : bestSolutionHoneyMask;

                    // Only expand into or swap with harvest cells.
                    // Or else we may expand in useless air directions.
                    if (grid[nr][nc] != FaceGrid.CELL_HARVEST || isAdjacent(materialMask, island.mask(), n)) continue;

                    // Expand island
                    if (!bestSolutionSlimeMask.get(nBit) && !bestSolutionHoneyMask.get(nBit)) {
                        materialMask.set(nBit);

                        bestSolution.set(i, island.withCell(n, nBit));

                        improved = true;
                        break;
                    }

                    island = tryTakeCell(i, island, n, nBit);
                }
            }
        }
    }

    /**
     * Try to steal the provided cell from a neighbor.
     * This allows other larger islands to expand and does not count as an improvement.
     *
     * @param i      the index of the current island we are trying to expand
     * @param island the current island we are trying to expand
     * @param n      the cell we are trying to steal from a neighbor
     * @param nBit   the bit index of the cell we are trying to steal from a neighbor
     * @return the updated current island
     */
    private Island tryTakeCell(int i, Island island, int n, int nBit) {
        for (int j = 0; j < bestSolution.size(); j++) {
            if (i == j) continue;
            Island neighboring = bestSolution.get(j);

            if (!neighboring.mask().get(nBit) || neighboring.cells().size() <= 4) continue;

            // Create the new neighboring island state after losing this cell.
            IntSet neighborNewCells = new IntOpenHashSet(neighboring.cells());
            BitSet neighborNewMask = (BitSet) neighboring.mask().clone();
            FlyingMachine neighborFlyingMachine = neighboring.flyingMachine();
            neighborNewCells.remove(n);
            neighborNewMask.clear(nBit);
            neighborNewCells = IntSets.unmodifiable(neighborNewCells);

            // If we steal part of the neighbor's flying machine.
            if (neighborFlyingMachine.stemMask().get(nBit) || neighborFlyingMachine.stopperCell() == n) {
                // Try to find a new flying machine.
                neighborFlyingMachine = findFlyingMachine(neighborNewCells);
                if (neighborFlyingMachine == null) {
                    continue;
                }
            }

            // Check the neighbor is still connected after losing this cell.
            if (!isConnected(neighborNewCells)) continue;

            // Update the material masks to reflect the cell transfer.
            if (island.material() == SLIME) {
                bestSolutionSlimeMask.set(nBit);
                bestSolutionHoneyMask.clear(nBit);
            } else {
                bestSolutionSlimeMask.clear(nBit);
                bestSolutionHoneyMask.set(nBit);
            }

            // Create the new island state after stealing this cell.
            // Update the island variable for the next iteration of the neighbor loop.
            bestSolution.set(i, island = island.withCell(n, nBit));
            bestSolution.set(j, new Island(neighborNewCells, neighborNewMask, neighborFlyingMachine, neighboring.material()));

            // This does not count as an improvement.
            // By breaking into the next iteration of the neighbor loop, this can cause us to miss some newly added neighbors.
            // This means cells can only steal from their original neighbors each iteration, which shouldn't be a big issue.
            break;
        }

        return island;
    }

    private boolean isAdjacent(BitSet flyingMachineStemMask, FlyingMachine newFlyingMachine) {
        return flyingMachineStemMask.intersects(newFlyingMachine.stemNeighborsMask());
    }

    private boolean isAdjacent(BitSet cellsMask, Shape newShape) {
        return cellsMask.intersects(newShape.neighborsMask);
    }

    private boolean isAdjacent(BitSet cellsMask, BitSet excluding, int key) {
        for (var dir : DIRECTIONS) {
            int adj = key + dir;
            int nr = keyRow(adj);
            int nc = keyCol(adj);

            if (nr >= 0 && nr < rows && nc >= 0 && nc < cols) {
                int bit = cellBit(nr, nc);
                if (cellsMask.get(bit) && !excluding.get(bit)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isConnected(IntSet cells) {
        if (cells.isEmpty()) return true;

        IntSet visited = new IntOpenHashSet(cells.size());
        IntPriorityQueue queue = new IntArrayFIFOQueue(cells.size());
        int start = cells.iterator().nextInt();
        queue.enqueue(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            int current = queue.dequeueInt();
            for (int dir : DIRECTIONS) {
                int neighbor = current + dir;
                if (cells.contains(neighbor) && visited.add(neighbor)) {
                    queue.enqueue(neighbor);
                }
            }
        }

        return visited.size() == cells.size();
    }
}
