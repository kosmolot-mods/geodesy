package pl.kosma.geodesy;

import com.google.common.collect.Sets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Tuple;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.kosma.geodesy.solver.*;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static net.minecraft.world.level.block.Block.UPDATE_CLIENTS;

public class GeodesyCore {

    // Build-time adjustments.
    static final int BUILD_MARGIN = 16;
    static final int WALL_OFFSET = 2;
    static final int CLOCK_Y_OFFSET = 13;
    static final Block WORK_AREA_WALL = Blocks.TINTED_GLASS;
    static final Block FULL_BLOCK = Blocks.IRON_BLOCK;
    static final Set<Block> MARKERS_BLOCKER = Sets.newHashSet(Blocks.WITHER_SKELETON_SKULL, Blocks.WITHER_SKELETON_WALL_SKULL);
    static final Set<Block> MARKERS_MACHINE = Sets.newHashSet(Blocks.ZOMBIE_HEAD, Blocks.ZOMBIE_WALL_HEAD);
    static final Set<Block> PRESERVE_BLOCKS = Sets.newHashSet(Blocks.BUDDING_AMETHYST, Blocks.COMMAND_BLOCK);
    static final Set<Block> STICKY_BLOCKS = Sets.newHashSet(Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK);
    static final Set<Block> PRESERVE_WALL_BLOCKS = Sets.newHashSet(Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK, Blocks.OBSIDIAN);

    static final Logger LOGGER = LoggerFactory.getLogger("GeodesyCore");

    private ServerLevel world;
    @Nullable
    private IterableBoundingBox geode;
    // The following list must contain all budding amethyst in the area.
    @Nullable
    private List<BlockPos> buddingAmethystPositions;
    @Nullable
    // The following list must contain all amethyst clusters in the area.
    private List<Tuple<BlockPos, Direction>> amethystClusterPositions;

    // The directions used in the last /geodesy project command.
    private Direction @Nullable [] lastProjectedDirections;
    // Used to makes sure another solve doesn't start while one is already running.
    private CompletableFuture<Void> solveFuture;

    public void geodesyGeodesy() {
        sendCommandFeedback("Welcome to Geodesy!");
        sendCommandFeedback("Read the book for all the gory details.");
        fillHotbar();
    }

    private void fillHotbar() {
        ServerPlayer player = this.player.get();
        if (player == null)
            return;
        player.getInventory().setItem(0, UnholyBookOfGeodesy.summonKrivbeknih());
        player.getInventory().setItem(1, Items.SLIME_BLOCK.getDefaultInstance());
        player.getInventory().setItem(2, Items.HONEY_BLOCK.getDefaultInstance());
        player.getInventory().setItem(3, Items.WITHER_SKELETON_SKULL.getDefaultInstance());
        player.getInventory().setItem(4, Items.ZOMBIE_HEAD.getDefaultInstance());
        player.getInventory().setItem(5, Items.AIR.getDefaultInstance());
        player.getInventory().setItem(6, Items.AIR.getDefaultInstance());
        player.getInventory().setItem(7, Items.AIR.getDefaultInstance());
        player.getInventory().setItem(8, Items.POISONOUS_POTATO.getDefaultInstance());
    }

    void geodesyArea(ServerLevel world, BlockPos startPos, BlockPos endPos) {
        sendCommandFeedback("---");

        this.world = world;

        // Detect the geode area.
        detectGeode(startPos, endPos);
        if (geode != null && buddingAmethystPositions != null) {
            prepareWorkArea(geode, true);
            countClusters(buddingAmethystPositions);
            highlightGeode(geode);
        }
    }

    public void geodesyAnalyze() {
        sendCommandFeedback("---");

        // Return if geodesy area has not been run yet.
        if (geode == null) {
            sendCommandFeedback("No area to analyze. Select an area with /geodesy area first.");
            return;
        }

        // Run all possible projections and show the efficiencies.
        sendCommandFeedback("Projection efficiency:");
        geodesyProject(new Direction[]{Direction.EAST});
        geodesyProject(new Direction[]{Direction.SOUTH});
        geodesyProject(new Direction[]{Direction.UP});
        geodesyProject(new Direction[]{Direction.EAST, Direction.SOUTH});
        geodesyProject(new Direction[]{Direction.EAST, Direction.UP});
        geodesyProject(new Direction[]{Direction.SOUTH, Direction.UP});
        geodesyProject(new Direction[]{Direction.EAST, Direction.SOUTH, Direction.UP});
        // Clean up the results of the last projection.
        geodesyProject(null);
        // Advise the user.
        sendCommandFeedback("Now run /geodesy project with your chosen projections.");
    }

    void geodesyProject(Direction[] directions) {
        // Return if geodesy area has not been run yet.
        if (geode == null || buddingAmethystPositions == null || amethystClusterPositions == null) {
            sendCommandFeedback("No area to analyze. Select an area with /geodesy area first.");
            return;
        }

        // Store the directions for later use by /geodesy solve.
        this.lastProjectedDirections = directions;

        // Expand the area and clear it out for work purposes.
        this.prepareWorkArea(geode, true);

        // Grow and count all amethyst (for efficiency calculation).
        this.growClusters(amethystClusterPositions);

        // Render a frame.
        IterableBoundingBox frameBoundingBox = new IterableBoundingBox(geode.inflatedBy(WALL_OFFSET));
        frameBoundingBox.forEachEdgePosition(blockPos -> world.setBlock(blockPos, Blocks.MOSS_BLOCK.defaultBlockState(), UPDATE_CLIENTS));

        // Do nothing more if we have no directions - this signifies we just want
        // to draw the frame and do nothing else.
        if (directions == null)
            return;


        // Run the projection.
        for (Direction direction : directions) {
            this.projectGeode(geode, buddingAmethystPositions, amethystClusterPositions, direction);
        }

        // Replace all remaining amethyst clusters with buttons so items can't
        // fall on them and get stuck.
        AtomicInteger clustersLeft = new AtomicInteger();
        amethystClusterPositions.forEach(blockPosDirectionPair -> {
            if (world.getBlockState(blockPosDirectionPair.getA()).getBlock() == Blocks.AMETHYST_CLUSTER) {
                clustersLeft.getAndIncrement();
                world.setBlock(blockPosDirectionPair.getA(), switch (blockPosDirectionPair.getB()) {
                    case DOWN -> Blocks.SPRUCE_BUTTON.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.CEILING);
                    case UP -> Blocks.SPRUCE_BUTTON.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR);
                    default -> Blocks.SPRUCE_BUTTON.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, blockPosDirectionPair.getB());
                }, UPDATE_CLIENTS);
            }
        });
        int clustersCollected = amethystClusterPositions.size() - clustersLeft.get();

        // Re-grow the buds so they are visible.
        this.growClusters(amethystClusterPositions);

        // Calculate and show layout efficiency.
        float efficiency = 100f * (clustersCollected) / amethystClusterPositions.size();
        String layoutName = String.join(" ", Arrays.stream(directions).map(Direction::toString).collect(Collectors.joining(" ")));
        sendCommandFeedback(" %s: %d%% (%d/%d)", layoutName, (int) efficiency, clustersCollected, amethystClusterPositions.size());
    }

    void geodesyProjectCommand(Direction[] directions) {
        sendCommandFeedback("---");

        geodesyProject(directions);

        sendCommandFeedback("Now run /geodesy solve to find optimal slime/honey block placement for this layout.");
        sendCommandFeedback("Alternatively, you can place blocks and skulls manually.");
    }

    // Solve for optimal slime/honey block placement. Must be run after /geodesy project.
    void geodesySolve() {
        geodesySolve(SolverConfig.defaults());
    }

    // Solve for optimal slime/honey block placement. Each face is solved in parallel.
    void geodesySolve(SolverConfig config) {
        sendCommandFeedback("---");

        MinecraftServer server = world.getServer();
        if (server == null) {
            sendCommandFeedback("Server instance not found. Are you sure you're running this on the server?");
            return;
        }

        // Validate that we have the required state.
        if (geode == null) {
            sendCommandFeedback("No geode detected. Run /geodesy area first.");
            return;
        }
        if (lastProjectedDirections == null || lastProjectedDirections.length == 0) {
            sendCommandFeedback("No projection found. Run /geodesy project first.");
            return;
        }

        // Clear any previous solver results (sticky blocks and mob heads)
        for (Direction direction : lastProjectedDirections) {
            clearSolverLayers(geode, direction);
        }

        String directionNames = Arrays.stream(lastProjectedDirections).map(Direction::toString).collect(Collectors.joining(", "));
        sendCommandFeedback("Solving %d face(s) in parallel: %s...", lastProjectedDirections.length, directionNames);

        // Extract all face grids first (must be done on main thread for world access)
        List<FaceGrid> faceGrids = new ArrayList<>(6);
        for (Direction direction : lastProjectedDirections) {
            FaceGrid faceGrid = extractFaceGrid(geode, direction);
            if (faceGrid != null) {
                faceGrids.add(faceGrid);
            } else {
                sendCommandFeedback("  %s: Failed to extract face grid.", direction);
            }
        }

        if (faceGrids.isEmpty()) {
            sendCommandFeedback("No faces to solve.");
            return;
        }

        if (solveFuture != null && !solveFuture.isDone()) {
            sendCommandFeedback("Solve already in progress. Please wait for it to finish before starting another.");
            return;
        }

        // Submit all solve tasks in parallel
        @SuppressWarnings("rawtypes")
        CompletableFuture[] futures = faceGrids.stream()
                .map(faceGrid -> solveFace(server, geode, config, faceGrid))
                .toArray(CompletableFuture[]::new);

        solveFuture = CompletableFuture.allOf(futures)
                .thenRun(() -> server.execute(() -> sendCommandFeedback("Solve complete. Run /geodesy assemble when ready.")));
    }

    private @NonNull CompletableFuture<Void> solveFace(@NotNull MinecraftServer server, @NotNull IterableBoundingBox geode, SolverConfig config, FaceGrid faceGrid) {
        // Create a new solver instance for each face (thread safety)
        return CompletableFuture.supplyAsync(() -> new BacktrackingFaceSolver(faceGrid, config).solve(faceGrid, config))
                .exceptionally(e -> {
                    LOGGER.error("Failed to solve face {}", faceGrid.direction(), e);
                    sendCommandFeedback("  %s: Failed to solve - %s", faceGrid.direction(), e.getMessage());
                    return SolverResult.empty(faceGrid);
                })
                .thenAccept(result -> server.execute(() -> {
                    // Apply the solution to the world (must be on main thread)
                    applySolverResult(geode, result.direction(), result);

                    // Report results
                    sendCommandFeedback("  %s: %.0f%% coverage (%d/%d), %d flying machines, %d blocks, %dms%s",
                            result.direction(),
                            result.getCoveragePercent(),
                            result.harvestCovered(),
                            result.totalHarvest(),
                            result.islands().size(),
                            result.getBlockCount(),
                            result.solveTimeMs(),
                            result.timedOut() ? " (timed out)" : ""
                    );
                }));
    }

    // Clears sticky blocks and mob heads for a face. Allows re-running /geodesy solve.
    private void clearSolverLayers(@NotNull IterableBoundingBox geode, Direction direction) {
        // Calculate grid dimensions based on the direction
        int width, height;
        switch (direction.getAxis()) {
            case X -> {
                width = geode.maxZ() - geode.minZ() + 1;
                height = geode.maxY() - geode.minY() + 1;
            }
            case Y -> {
                width = geode.maxX() - geode.minX() + 1;
                height = geode.maxZ() - geode.minZ() + 1;
            }
            case Z -> {
                width = geode.maxX() - geode.minX() + 1;
                height = geode.maxY() - geode.minY() + 1;
            }
            default -> {
                return;
            }
        }

        // Clear both layers (wall+1 for sticky blocks, wall+2 for mob heads)
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                setMutableToWallPos(geode, mutablePos, direction, x, y);

                // Clear sticky block layer (wall + 1)
                mutablePos.move(direction, 1);
                Block stickyBlock = world.getBlockState(mutablePos).getBlock();
                if (STICKY_BLOCKS.contains(stickyBlock)) {
                    world.setBlock(mutablePos, Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS);
                }

                // Clear mob head layer (wall + 2)
                mutablePos.move(direction, 1);
                Block headBlock = world.getBlockState(mutablePos).getBlock();
                if (MARKERS_BLOCKER.contains(headBlock) || MARKERS_MACHINE.contains(headBlock)) {
                    world.setBlock(mutablePos, Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS);
                }
            }
        }
    }

    // Extracts a FaceGrid from the world. Reads wall blocks placed by /geodesy project.
    @Nullable
    private FaceGrid extractFaceGrid(@NotNull IterableBoundingBox geode, Direction direction) {
        // Calculate grid dimensions based on the direction.
        // For each direction, we need to map the wall to a 2D grid.
        int width, height;
        switch (direction.getAxis()) {
            case X -> {
                // East/West face: Z is width, Y is height
                width = geode.maxZ() - geode.minZ() + 1;
                height = geode.maxY() - geode.minY() + 1;
            }
            case Y -> {
                // Up/Down face: X is width, Z is height
                width = geode.maxX() - geode.minX() + 1;
                height = geode.maxZ() - geode.minZ() + 1;
            }
            case Z -> {
                // North/South face: X is width, Y is height
                width = geode.maxX() - geode.minX() + 1;
                height = geode.maxY() - geode.minY() + 1;
            }
            default -> {
                return null;
            }
        }

        FaceGrid grid = new FaceGrid(width, height, direction);

        // Iterate through the wall and populate the grid.
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                setMutableToWallPos(geode, mutablePos, direction, x, y);
                Block block = world.getBlockState(mutablePos).getBlock();

                byte cellValue;
                if (block == Blocks.CRYING_OBSIDIAN) {
                    cellValue = FaceGrid.CELL_BLOCKED;
                } else if (block == Blocks.PUMPKIN) {
                    cellValue = FaceGrid.CELL_HARVEST;
                } else {
                    cellValue = FaceGrid.CELL_AIR;
                }
                grid.setCell(x, y, cellValue);
            }
        }

        return grid;
    }

    // Converts grid coordinates to world wall position.
    private static BlockPos.MutableBlockPos gridToWallPos(@NotNull IterableBoundingBox geode, Direction direction, int gridX, int gridY) {
        return switch (direction) {
            case EAST -> new BlockPos.MutableBlockPos(
                    geode.maxX() + WALL_OFFSET,
                    geode.minY() + gridY,
                    geode.minZ() + gridX);
            case WEST -> new BlockPos.MutableBlockPos(
                    geode.minX() - WALL_OFFSET,
                    geode.minY() + gridY,
                    geode.minZ() + gridX);
            case UP -> new BlockPos.MutableBlockPos(
                    geode.minX() + gridX,
                    geode.maxY() + WALL_OFFSET,
                    geode.minZ() + gridY);
            case DOWN -> new BlockPos.MutableBlockPos(
                    geode.minX() + gridX,
                    geode.minY() - WALL_OFFSET,
                    geode.minZ() + gridY);
            case SOUTH -> new BlockPos.MutableBlockPos(
                    geode.minX() + gridX,
                    geode.minY() + gridY,
                    geode.maxZ() + WALL_OFFSET);
            case NORTH -> new BlockPos.MutableBlockPos(
                    geode.minX() + gridX,
                    geode.minY() + gridY,
                    geode.minZ() - WALL_OFFSET);
        };
    }

    // Sets a mutable BlockPos to the wall position for given grid coordinates.
    private static void setMutableToWallPos(@NotNull IterableBoundingBox geode, BlockPos.MutableBlockPos pos, Direction direction, int gridX, int gridY) {
        switch (direction) {
            case EAST -> pos.set(geode.maxX() + WALL_OFFSET, geode.minY() + gridY, geode.minZ() + gridX);
            case WEST -> pos.set(geode.minX() - WALL_OFFSET, geode.minY() + gridY, geode.minZ() + gridX);
            case UP -> pos.set(geode.minX() + gridX, geode.maxY() + WALL_OFFSET, geode.minZ() + gridY);
            case DOWN -> pos.set(geode.minX() + gridX, geode.minY() - WALL_OFFSET, geode.minZ() + gridY);
            case SOUTH -> pos.set(geode.minX() + gridX, geode.minY() + gridY, geode.maxZ() + WALL_OFFSET);
            case NORTH -> pos.set(geode.minX() + gridX, geode.minY() + gridY, geode.minZ() - WALL_OFFSET);
        }
    }

    // Applies solver result: places slime/honey blocks and mob heads.
    private void applySolverResult(@NotNull IterableBoundingBox geode, Direction direction, SolverResult result) {
        // Place sticky blocks for each cell
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < result.width(); x++) {
            for (int y = 0; y < result.height(); y++) {
                byte placement = result.getPlacement(x, y);
                if (placement == 0) {
                    continue;
                }

                // Calculate the position one block outside the wall (where sticky blocks go).
                setMutableToWallPos(geode, mutablePos, direction, x, y);
                mutablePos.move(direction, 1);

                Block blockToPlace = switch (placement) {
                    case AbstractFaceSolver.SLIME -> Blocks.SLIME_BLOCK;
                    case AbstractFaceSolver.HONEY -> Blocks.HONEY_BLOCK;
                    default -> null;
                };

                if (blockToPlace != null) {
                    world.setBlock(mutablePos, blockToPlace.defaultBlockState(), UPDATE_CLIENTS);
                }
            }
        }

        // Place mob heads for each island
        for (AbstractFaceSolver.Island island : result.islands()) {
            placeMobHeadsForIsland(geode, direction, island);
        }
    }

    // Places mob heads in L-shape pattern: 3 zombie heads + 1 wither skeleton skull.
    // Uses the pre-computed L-shape data from the solver result.
    private void placeMobHeadsForIsland(@NotNull IterableBoundingBox geode, Direction direction, AbstractFaceSolver.Island island) {
        if (island.flyingMachine() == null || island.flyingMachine().stemCells() == null || island.flyingMachine().stemCells().size() != 3) {
            LOGGER.warn("Island with unexpected L-shape: {}. Island: {}", island.flyingMachine(), island);
            sendCommandFeedback("Island with unexpected L-shape: %s. Island: %s", island.flyingMachine(), island);
            return;
        }

        // Place zombie heads on stem cells, wither skeleton skull on corner
        for (int cell : island.flyingMachine().stemCells()) {
            BlockPos headPos = gridToWallPos(geode, direction, AbstractFaceSolver.keyRow(cell), AbstractFaceSolver.keyCol(cell)).move(direction, 2);
            placeSkull(headPos, Blocks.ZOMBIE_HEAD, Blocks.ZOMBIE_WALL_HEAD, direction);
        }

        int stopperCell = island.flyingMachine().stopperCell();
        BlockPos stopperPos = gridToWallPos(geode, direction, AbstractFaceSolver.keyRow(stopperCell), AbstractFaceSolver.keyCol(stopperCell)).move(direction, 2);
        placeSkull(stopperPos, Blocks.WITHER_SKELETON_SKULL, Blocks.WITHER_SKELETON_WALL_SKULL, direction);
    }

    // Places a skull: wall variant for horizontal faces, floor variant for up/down.
    private void placeSkull(BlockPos pos, Block floorVariant, Block wallVariant, Direction faceDirection) {
        if (faceDirection == Direction.UP || faceDirection == Direction.DOWN) {
            // Floor variant for up/down faces
            world.setBlock(pos, floorVariant.defaultBlockState(), UPDATE_CLIENTS);
        } else {
            // Wall variant for horizontal faces
            world.setBlock(pos, wallVariant.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, faceDirection), UPDATE_CLIENTS);
        }
    }

    void geodesyAssemble() {
        // Return if geodesy area has not been run yet.
        if (geode == null) {
            sendCommandFeedback("No area to analyze. Select an area with /geodesy area first.");
            return;
        }

        // Plop the clock at the top
        BlockPos clockPos = new BlockPos((geode.minX() + geode.maxX()) / 2 + 3, geode.maxY() + CLOCK_Y_OFFSET, (geode.minZ() + geode.maxZ()) / 2 + 1);
        BlockPos torchPos = buildClock(clockPos, Direction.WEST, Direction.NORTH);

        // Run along all the axes and move all slime/honey blocks inside the frame.
        for (Direction direction : Direction.values()) {
            geode.slice(direction.getAxis(), slice -> {
                // Calculate positions of the source and target blocks for moving.
                BlockPos targetPos = slice.getEndpoint(direction).relative(direction, WALL_OFFSET);
                BlockPos sourcePos = targetPos.relative(direction, 1);
                Block sourceBlock = world.getBlockState(sourcePos).getBlock();
                // Check that the operation can succeed.
                if (STICKY_BLOCKS.contains(sourceBlock)) {
                    world.setBlock(targetPos, world.getBlockState(sourcePos), UPDATE_CLIENTS);
                    world.setBlock(sourcePos, Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS);
                }
            });
        }

        // Check each slice for a marker block.
        for (Direction slicingDirection : Direction.values()) {
            List<BlockPos> triggerObserverPositions = new ArrayList<>();
            geode.slice(slicingDirection.getAxis(), slice -> {
                // Check for blocker marker block.
                BlockPos blockerPos = slice.getEndpoint(slicingDirection).relative(slicingDirection, WALL_OFFSET + 2);
                BlockPos oppositeWallPos = slice.getEndpoint(slicingDirection.getOpposite()).relative(slicingDirection, -WALL_OFFSET);
                if (!MARKERS_BLOCKER.contains(world.getBlockState(blockerPos).getBlock()))
                    return;
                // Read the sticky block at blocker position.
                BlockPos stickyPos = slice.getEndpoint(slicingDirection).relative(slicingDirection, WALL_OFFSET);
                Block stickyBlock = world.getBlockState(stickyPos).getBlock();

                // Find the position of the first machine block.
                BlockPos firstMachinePos = null;
                for (Direction direction : Direction.values()) {
                    // Check there is a machine marker block and the sticky block is the correct type
                    if (MARKERS_MACHINE.contains(world.getBlockState(blockerPos.relative(direction)).getBlock())
                            && world.getBlockState(blockerPos.relative(direction).relative(slicingDirection.getOpposite(), 2)).getBlock() == stickyBlock) {
                        firstMachinePos = blockerPos.relative(direction);
                        break;
                    }
                }
                if (firstMachinePos == null)
                    return;

                // First the direction of the second (and third) machine block.
                Direction machineDirection = null;
                for (Direction direction : Direction.values()) {
                    // Check there is a machine marker block and the sticky block is the correct type
                    if (MARKERS_MACHINE.contains(world.getBlockState(firstMachinePos.relative(direction, 1)).getBlock())
                            && MARKERS_MACHINE.contains(world.getBlockState(firstMachinePos.relative(direction, 2)).getBlock())
                            && world.getBlockState(firstMachinePos.relative(direction, 1).relative(slicingDirection.getOpposite(), 2)).getBlock() == stickyBlock
                            && world.getBlockState(firstMachinePos.relative(direction, 2).relative(slicingDirection.getOpposite(), 2)).getBlock() == stickyBlock) {
                        machineDirection = direction;
                        break;
                    }
                    // Check there is a machine marker block and the sticky block is the correct type
                    if (MARKERS_MACHINE.contains(world.getBlockState(firstMachinePos.relative(direction, -1)).getBlock())
                            && MARKERS_MACHINE.contains(world.getBlockState(firstMachinePos.relative(direction, 1)).getBlock())
                            && world.getBlockState(firstMachinePos.relative(direction, -1).relative(slicingDirection.getOpposite(), 2)).getBlock() == stickyBlock
                            && world.getBlockState(firstMachinePos.relative(direction, 1).relative(slicingDirection.getOpposite(), 2)).getBlock() == stickyBlock) {
                        firstMachinePos = firstMachinePos.relative(direction, -1);
                        machineDirection = direction;
                        break;
                    }
                }
                if (machineDirection == null)
                    return;

                // We need the opposite sticky block for the flying machine.
                if (stickyBlock == Blocks.SLIME_BLOCK)
                    stickyBlock = Blocks.HONEY_BLOCK;
                else if (stickyBlock == Blocks.HONEY_BLOCK)
                    stickyBlock = Blocks.SLIME_BLOCK;
                else
                    return;
                // Important: the actual machine is built one block closer to the geode
                // than the player-placed markers are. Also wipe out the blocker marker because
                // it doesn't get removed otherwise.
                world.setBlock(blockerPos, Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS);
                blockerPos = blockerPos.relative(slicingDirection.getOpposite());
                firstMachinePos = firstMachinePos.relative(slicingDirection.getOpposite());
                // All good - build the machine.
                BlockPos triggerObserverPosition = buildMachine(blockerPos, firstMachinePos, slicingDirection, machineDirection, stickyBlock, oppositeWallPos);
                triggerObserverPositions.add(triggerObserverPosition);
            });

            // Do nothing for this direction if there's no machines - the wiring logic would fail.
            if (triggerObserverPositions.isEmpty())
                continue;

            // Run the wiring building logic.
            if (slicingDirection == Direction.UP) {
                buildTriggerWiringUp(triggerObserverPositions, torchPos);
            } else if (slicingDirection != Direction.DOWN) {
                buildTriggerWiringHorizontal(triggerObserverPositions, slicingDirection);
            } else {
                // Direction.DOWN... no support for automatic wiring for that.
            }
        }

        // Fill all gaps in walls with moss. This should be way cheaper than using tons of obsidian.
        IterableBoundingBox wallsBox = new IterableBoundingBox(geode.expand(WALL_OFFSET));
        buildWalls(wallsBox);

        // Generate the water collection system.
        WaterCollectionSystemGenerator.generate(world, geode.expand(WALL_OFFSET - 1));
    }

    private void buildTriggerWiringHorizontal(List<BlockPos> observerPositions, Direction slicingDirection) {
        // Skip the wiring if there are no observers.
        if (observerPositions.isEmpty())
            return;

        // Calculate the volume containing the trigger observers.
        BoundingBox observersVolume = BoundingBox.encapsulatingPositions(observerPositions).orElseThrow();

        // Calculate the lower edge of the trigger volume (shaped 1x1xN blocks).
        IterableBoundingBox triggerVolumeLowerEdge = new IterableBoundingBox(
                observersVolume.minX(), observersVolume.minY(), observersVolume.minZ(),
                observersVolume.maxX(), observersVolume.minY(), observersVolume.maxZ());

        // Build the trigger wiring along the lower edge.
        triggerVolumeLowerEdge.forEachPosition(triggerPos -> {
            // Calculate the 1x1 vertical box that encompasses all the possible observer positions in the current slice.
            IterableBoundingBox observersBox = new IterableBoundingBox(
                triggerPos.getX(), observersVolume.minY(), triggerPos.getZ(),
                triggerPos.getX(), observersVolume.maxY(), triggerPos.getZ());

            // Create a list of all needed scaffolding positions in this slice.
            List<BlockPos> scaffoldingPositions = observerPositions.stream()
                    // Extract all the observers belonging to the current slice
                    .filter(observersBox::isInside)
                    // Offset them all one block out, to become needed scaffolding positions
                    .map(blockPos -> blockPos.relative(slicingDirection))
                    .collect(Collectors.toList());
            if (!scaffoldingPositions.isEmpty()) {
                // Add the bottom scaffolding, which is always needed no matter what.
                scaffoldingPositions.add(triggerPos.relative(slicingDirection));
            }

            /*
             * Wiring:
             *
             *  n <#
             *  1 <#
             *  0 o##
             * -1  -@.
             * -2    F
             *    0123
             *
             * o build origin (lower block of the observer tower) a.k.a. triggerPos
             * < observer
             * # scaffolding
             * - trapdoor
             * @ target
             * . redstone dust
             * F full block
             *
             * In slices without observers, the only blocks placed are solid and redstone dust.
             */

            // Place solid block and redstone wire - these are always placed.
            world.setBlock(triggerPos.relative(slicingDirection, 3).relative(Direction.UP, -2), FULL_BLOCK.defaultBlockState(), UPDATE_CLIENTS);
            world.setBlock(triggerPos.relative(slicingDirection, 3).relative(Direction.UP, -1), Blocks.REDSTONE_WIRE.defaultBlockState(), UPDATE_CLIENTS);

            // The rest of wiring only applies if there are any observers present.
            if (!scaffoldingPositions.isEmpty()) {
                world.setBlock(triggerPos.relative(slicingDirection, 1).relative(Direction.UP, -1), Blocks.IRON_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, slicingDirection).setValue(TrapDoorBlock.HALF, Half.TOP), UPDATE_CLIENTS);
                world.setBlock(triggerPos.relative(slicingDirection, 2).relative(Direction.UP, -1), Blocks.TARGET.defaultBlockState(), UPDATE_CLIENTS);
                world.setBlock(triggerPos.relative(slicingDirection, 2).relative(Direction.UP, 0), Blocks.SCAFFOLDING.defaultBlockState().setValue(ScaffoldingBlock.DISTANCE, 0), UPDATE_CLIENTS);
                IterableBoundingBox scaffoldingBox = new IterableBoundingBox(BoundingBox.encapsulatingPositions(scaffoldingPositions).orElseThrow());
                scaffoldingBox.forEachPosition(scaffoldingPos ->
                        world.setBlock(scaffoldingPos, Blocks.SCAFFOLDING.defaultBlockState().setValue(ScaffoldingBlock.DISTANCE, 0), UPDATE_CLIENTS)
                );
            }
        });

        // Place all the observers last, so they don't trigger.
        observerPositions.forEach(observerPos ->
                world.setBlock(observerPos, Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, slicingDirection), UPDATE_CLIENTS)
        );
    }

    private void buildTriggerWiringUp(List<BlockPos> observerPositions, BlockPos torchPos) {
        /*
         * On the top, the trigger wiring is a simple X-Y grid of redstone dust:
         * 1. On the X axis, there is a line running across the entire trigger area up to the torch.
              This line is on the Z coordinate of the torch.
         * 2. On the Z axis, there are lines running from each trigger point down to the center line.
         */

        List<IterableBoundingBox> lines = new ArrayList<>();

        // The Z lines are per-observer.
        observerPositions.forEach(blockPos -> lines.add(new IterableBoundingBox(
                blockPos.getX(), blockPos.getY(), blockPos.getZ(),
                blockPos.getX(), blockPos.getY(), torchPos.getZ())
        ));

        // The X axis line needs to connect to the trigger position area - add it.
        // It's okay to modify it here, as no other code uses it.
        observerPositions.add(torchPos.relative(Direction.DOWN, 2));
        IterableBoundingBox xLineArea = new IterableBoundingBox(BoundingBox.encapsulatingPositions(observerPositions).orElseThrow());
        IterableBoundingBox xLine = new IterableBoundingBox(
            xLineArea.minX(), xLineArea.minY(), torchPos.getZ(),
            xLineArea.maxX(), xLineArea.minY(), torchPos.getZ());
        lines.add(xLine);

        // Create all the lines with redstone dust on top.
        lines.forEach(blockBox -> blockBox.forEachPosition(blockPos -> {
            // Place a solid block if it's not already there.
            if (world.getBlockState(blockPos).getBlock() == Blocks.AIR)
                world.setBlockAndUpdate(blockPos, FULL_BLOCK.defaultBlockState());
            // Place redstone dust on top if it's not already there.
            if (world.getBlockState(blockPos.relative(Direction.UP)).getBlock() == Blocks.AIR)
                world.setBlockAndUpdate(blockPos.relative(Direction.UP), Blocks.REDSTONE_WIRE.defaultBlockState());
        }));
    }

    private void buildWalls(IterableBoundingBox wallsBox) {
        for (Direction slicingDirection : Direction.values()) {
            // Top wall (lid) is transparent, but we still run the processing
            // to remove all blocks that should be removed.
            BlockState wallBlock = (slicingDirection == Direction.UP) ?
                    Blocks.AIR.defaultBlockState() :
                    Blocks.MOSS_BLOCK.defaultBlockState();
            wallsBox.slice(slicingDirection.getAxis(), iterableBoundingBox -> {
                BlockPos end = iterableBoundingBox.getEndpoint(slicingDirection);
                if (!PRESERVE_WALL_BLOCKS.contains(world.getBlockState(end).getBlock()))
                    world.setBlockAndUpdate(end, wallBlock);
            });
        }
    }

    @SuppressWarnings("SameParameterValue")
    private void prepareWorkArea(@NotNull IterableBoundingBox geode, boolean force) {
        IterableBoundingBox workBoundingBox = new IterableBoundingBox(geode.expand(BUILD_MARGIN));
        BlockPos commandBlockPos = new BlockPos(workBoundingBox.maxX(), workBoundingBox.maxY(), workBoundingBox.maxZ());

        // Check for existing command block, bail out if found.
        if (!force) {
            if (world.getBlockState(commandBlockPos).getBlock() == Blocks.COMMAND_BLOCK)
                return;
        }

        // Wipe out the area (except stuff we preserve)
        workBoundingBox.forEachPosition(blockPos -> {
            if (!PRESERVE_BLOCKS.contains(world.getBlockState(blockPos).getBlock()))
                world.setBlock(blockPos, Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS);
        });

        // Place walls inside to prevent water and falling blocks from going bonkers.
        IterableBoundingBox wallsBoundingBox = new IterableBoundingBox(workBoundingBox.expand(1));
        wallsBoundingBox.forEachWallPosition(blockPos -> {
            if (!PRESERVE_BLOCKS.contains(world.getBlockState(blockPos).getBlock()))
                world.setBlock(blockPos, WORK_AREA_WALL.defaultBlockState(), UPDATE_CLIENTS);
        });

        // Add a command block to allow the player to reexecute the command easily.
        String resumeCommand = String.format("/geodesy area %d %d %d %d %d %d",
                geode.minX(), geode.minY(), geode.minZ(), geode.maxX(), geode.maxY(), geode.maxZ());

        world.setBlock(commandBlockPos, Blocks.COMMAND_BLOCK.defaultBlockState(), UPDATE_CLIENTS);
        CommandBlockEntity commandBlock = (CommandBlockEntity) world.getBlockEntity(commandBlockPos);
        if (commandBlock == null) {
            LOGGER.error("Command blocks are disabled on the server - unable to save the resume command.");
            return;
        }
        commandBlock.getCommandBlock().setCommand(resumeCommand);
        commandBlock.setChanged();
    }

    private void detectGeode(BlockPos pos1, BlockPos pos2) {
        // Calculate the correct min/max coordinates and construct a box.
        IterableBoundingBox scanBox = new IterableBoundingBox(new BoundingBox(
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ()),
                Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ())
        ));

        // Scan the box, marking any positions with budding amethyst, and
        // calculate the minimum bounding box that contains these positions.
        buddingAmethystPositions = new ArrayList<>();
        AtomicInteger minX = new AtomicInteger(Integer.MAX_VALUE);
        AtomicInteger minY = new AtomicInteger(Integer.MAX_VALUE);
        AtomicInteger minZ = new AtomicInteger(Integer.MAX_VALUE);
        AtomicInteger maxX = new AtomicInteger(Integer.MIN_VALUE);
        AtomicInteger maxY = new AtomicInteger(Integer.MIN_VALUE);
        AtomicInteger maxZ = new AtomicInteger(Integer.MIN_VALUE);
        scanBox.forEachPosition(blockPos -> {
            if (world.getBlockState(blockPos).getBlock() == Blocks.BUDDING_AMETHYST) {
                buddingAmethystPositions.add(blockPos);
                // Expand the bounding box to include this position.
                if (blockPos.getX() < minX.get()) minX.set(blockPos.getX());
                if (blockPos.getX() > maxX.get()) maxX.set(blockPos.getX());
                if (blockPos.getY() < minY.get()) minY.set(blockPos.getY());
                if (blockPos.getY() > maxY.get()) maxY.set(blockPos.getY());
                if (blockPos.getZ() < minZ.get()) minZ.set(blockPos.getZ());
                if (blockPos.getZ() > maxZ.get()) maxZ.set(blockPos.getZ());
            }
        });

        if (minX.get() == Integer.MAX_VALUE) {
            sendCommandFeedback("I can't find any budding amethyst in the area you gave me. :(");
            geode = null;
            amethystClusterPositions = null;
            return;
        } else {
            sendCommandFeedback("Geode found. Now verify it's detected correctly and run /geodesy analyze.");
        }
        // Expand 1 to make sure we grab all the amethyst clusters as well.
        geode = new IterableBoundingBox(minX.get(), minY.get(), minZ.get(), maxX.get(), maxY.get(), maxZ.get()).expand(1);
    }

    private void highlightGeode(@NotNull IterableBoundingBox geode) {
        // Highlight the geode area.
        int commandBlockOffset = WALL_OFFSET + 1;
        BlockPos structureBlockPos = new BlockPos(geode.minX() - commandBlockOffset, geode.minY() - commandBlockOffset, geode.minZ() - commandBlockOffset);
        BlockState structureBlockState = Blocks.STRUCTURE_BLOCK.defaultBlockState().setValue(StructureBlock.MODE, StructureMode.SAVE);
        world.setBlock(structureBlockPos, structureBlockState, UPDATE_CLIENTS);
        StructureBlockEntity structure = (StructureBlockEntity) world.getBlockEntity(structureBlockPos);
        if (structure == null) {
            LOGGER.error("StructureBlock tile entity is missing... this should never happen????");
            return;
        }
        structure.setStructureName("geodesy:geode");
        structure.setStructurePos(new BlockPos(commandBlockOffset, commandBlockOffset, commandBlockOffset));
        structure.setStructureSize(geode.getLength().offset(1, 1, 1));
        structure.setShowBoundingBox(true);
        structure.setChanged();
    }

    /**
     * Count all possible clusters from each budding block.
     */
    private void countClusters(@NotNull List<BlockPos> buddingAmethystPositions) {
        amethystClusterPositions = new ArrayList<>();
        buddingAmethystPositions.forEach(blockPos -> {
            for (Direction direction : Direction.values()) {
                BlockPos budPos = blockPos.relative(direction);
                if (world.getBlockState(budPos).getBlock() == Blocks.AIR) {
                    amethystClusterPositions.add(new Tuple<>(budPos, direction));
                }
            }
        });
    }

    /**
     * Set all the clusters positions to cluster blocks if it is currently air.
     */
    private void growClusters(@NotNull List<Tuple<BlockPos, Direction>> amethystClusterPositions) {
        amethystClusterPositions.forEach(blockPosDirectionPair -> {
            if (world.getBlockState(blockPosDirectionPair.getA()).getBlock() == Blocks.AIR) {
                world.setBlockAndUpdate(blockPosDirectionPair.getA(), Blocks.AMETHYST_CLUSTER.defaultBlockState().setValue(AmethystClusterBlock.FACING, blockPosDirectionPair.getB()));
            }
        });
    }

    /**
     * Project the geode to a plane in a direction
     * Slices with budding amethyst(s) are marked with crying obsidian.
     * Slices with amethyst cluster(s) that needs to be harvested are marked with pumpkin.
     *
     * @param direction The direction to project the geode to.
     * @author Kosma Moczek, Kevinthegreat
     */
    private void projectGeode(@NotNull IterableBoundingBox geode, List<BlockPos> buddingAmethystPositions, List<Tuple<BlockPos, Direction>> amethystClusterPositions, Direction direction) {
        // Mark wall for slices with budding amethysts.
        buddingAmethystPositions.forEach(blockPos -> {
            BlockPos wallPos = getWallPos(geode, blockPos, direction);
            world.setBlock(wallPos, Blocks.CRYING_OBSIDIAN.defaultBlockState(), UPDATE_CLIENTS);
        });
        // Mark wall for slices that needs to be harvested and have no budding amethysts.
        amethystClusterPositions.forEach(blockPosDirectionPair -> {
            // Check if the cluster is harvested.
            if (world.getBlockState(blockPosDirectionPair.getA()).getBlock() == Blocks.AMETHYST_CLUSTER) {
                BlockPos wallPos = getWallPos(geode, blockPosDirectionPair.getA(), direction);
                BlockPos oppositeWallPos = getWallPos(geode, blockPosDirectionPair.getA(), direction.getOpposite());
                // Check if the slice is marked with budding amethyst.
                if (world.getBlockState(wallPos).getBlock() != Blocks.CRYING_OBSIDIAN) {
                    // Mark all clusters in the slice as harvested.
                    BlockPos.betweenClosed(wallPos, oppositeWallPos).forEach(pos ->
                            world.setBlock(pos, Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS)
                    );
                    world.setBlock(wallPos, Blocks.PUMPKIN.defaultBlockState(), UPDATE_CLIENTS);
                }
            }
        });
    }

    /**
     * Get the position on the wall (with wall offset) of the geode bounding box for a position in a direction.
     *
     * @param blockPos  The block position.
     * @param direction The direction.
     * @return The position on the wall (with wall offset).
     * @author Kevinthegreat
     */
    private static BlockPos getWallPos(@NotNull IterableBoundingBox geode, BlockPos blockPos, Direction direction) {
        return switch (direction) {
            case EAST -> new BlockPos(geode.maxX() + WALL_OFFSET, blockPos.getY(), blockPos.getZ());
            case WEST -> new BlockPos(geode.minX() - WALL_OFFSET, blockPos.getY(), blockPos.getZ());
            case UP -> new BlockPos(blockPos.getX(), geode.maxY() + WALL_OFFSET, blockPos.getZ());
            case DOWN -> new BlockPos(blockPos.getX(), geode.minY() - WALL_OFFSET, blockPos.getZ());
            case SOUTH -> new BlockPos(blockPos.getX(), blockPos.getY(), geode.maxZ() + WALL_OFFSET);
            case NORTH -> new BlockPos(blockPos.getX(), blockPos.getY(), geode.minZ() - WALL_OFFSET);
        };
    }

    private BlockPos buildMachine(BlockPos blockerPos, BlockPos pos, Direction directionAlong, Direction directionUp, Block stickyBlock, BlockPos oppositeWallPos) {
        /*
         * It looks like this:
         * S HHH
         * S HVHH[<N<
         * SB[L>]SSSB
         */
        // Blocker block.
        world.setBlock(blockerPos, Blocks.OBSIDIAN.defaultBlockState(), UPDATE_CLIENTS);
        // Clear out the machine marker blocks.
        world.setBlock(pos.relative(directionUp, 0), Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS);
        world.setBlock(pos.relative(directionUp, 1), Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS);
        world.setBlock(pos.relative(directionUp, 2), Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS);
        pos = pos.relative(directionAlong, 1);
        // First layer: piston, 2 slime
        world.setBlock(pos.relative(directionUp, 0), Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, directionAlong.getOpposite()), UPDATE_CLIENTS);
        world.setBlock(pos.relative(directionUp, 1), stickyBlock.defaultBlockState(), UPDATE_CLIENTS);
        world.setBlock(pos.relative(directionUp, 2), stickyBlock.defaultBlockState(), UPDATE_CLIENTS);
        pos = pos.relative(directionAlong, 1);
        // Second layer: redstone lamp, observer, slime (order is important)
        world.setBlock(pos.relative(directionUp, 2), stickyBlock.defaultBlockState(), UPDATE_CLIENTS);
        world.setBlock(pos.relative(directionUp, 1), Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, directionUp), UPDATE_CLIENTS);
        world.setBlock(pos.relative(directionUp, 0), Blocks.REDSTONE_LAMP.defaultBlockState(), UPDATE_CLIENTS);
        pos = pos.relative(directionAlong, 1);
        // Third layer: observer, slime, slime
        world.setBlock(pos.relative(directionUp, 0), Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, directionAlong.getOpposite()), UPDATE_CLIENTS);
        world.setBlock(pos.relative(directionUp, 1), stickyBlock.defaultBlockState(), UPDATE_CLIENTS);
        world.setBlock(pos.relative(directionUp, 2), stickyBlock.defaultBlockState(), UPDATE_CLIENTS);
        pos = pos.relative(directionAlong, 1);
        // Fourth layer: piston, slime
        world.setBlock(pos.relative(directionUp, 0), Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, directionAlong), UPDATE_CLIENTS);
        world.setBlock(pos.relative(directionUp, 1), stickyBlock.defaultBlockState(), UPDATE_CLIENTS);
        pos = pos.relative(directionAlong, 1);
        // Fifth layer: slime, piston
        world.setBlock(pos.relative(directionUp, 0), Blocks.SLIME_BLOCK.defaultBlockState(), UPDATE_CLIENTS);
        world.setBlock(pos.relative(directionUp, 1), Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, directionAlong.getOpposite()), UPDATE_CLIENTS);
        pos = pos.relative(directionAlong, 2);
        // [SKIP!] Seventh layer: slime, note block
        world.setBlock(pos.relative(directionUp, 0), Blocks.SLIME_BLOCK.defaultBlockState(), UPDATE_CLIENTS);
        world.setBlock(pos.relative(directionUp, 1), Blocks.NOTE_BLOCK.defaultBlockState(), UPDATE_CLIENTS);
        pos = pos.relative(directionAlong, -1);
        // [GO BACK!] Sixth layer: slime, observer
        // This one is tricky, we initially set the observer in a wrong direction
        // so the note block tune change is not triggered.
        world.setBlock(pos.relative(directionUp, 1), Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, directionUp), UPDATE_CLIENTS);
        world.setBlock(pos.relative(directionUp, 0), Blocks.SLIME_BLOCK.defaultBlockState(), UPDATE_CLIENTS);
        world.setBlock(pos.relative(directionUp, 1), Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, directionAlong), UPDATE_CLIENTS);
        pos = pos.relative(directionAlong, 2);
        // [SKIP AGAIN!] Eighth layer: blocker
        world.setBlock(pos.relative(directionUp, 0), Blocks.OBSIDIAN.defaultBlockState(), UPDATE_CLIENTS);

        // Also blocker on the other end (the other wall).
        world.setBlock(oppositeWallPos, Blocks.OBSIDIAN.defaultBlockState(), UPDATE_CLIENTS);

        // Return the position of the observer that can trigger the machine.
        // It will be used later (in separate logic) to create the trigger wiring.
        return pos.relative(directionUp, 1);
    }

    @SuppressWarnings("SameParameterValue")
    private BlockPos buildClock(BlockPos startPos, Direction directionMain, Direction directionSide) {
        // Platform
        for (int i = 0; i < 6; i++)
            for (int j = 0; j < 3; j++)
                world.setBlockAndUpdate(startPos.relative(directionMain, i).relative(directionSide, j), FULL_BLOCK.defaultBlockState());

        // Four solid blocks
        world.setBlockAndUpdate(startPos.relative(directionMain, 0).relative(directionSide, 1).relative(Direction.UP, 1), FULL_BLOCK.defaultBlockState());
        world.setBlockAndUpdate(startPos.relative(directionMain, 0).relative(directionSide, 2).relative(Direction.UP, 1), FULL_BLOCK.defaultBlockState());
        world.setBlockAndUpdate(startPos.relative(directionMain, 5).relative(directionSide, 1).relative(Direction.UP, 1), FULL_BLOCK.defaultBlockState());
        world.setBlockAndUpdate(startPos.relative(directionMain, 5).relative(directionSide, 2).relative(Direction.UP, 1), FULL_BLOCK.defaultBlockState());

        // Lever
        world.setBlockAndUpdate(startPos.relative(directionMain, -1).relative(directionSide, 2).relative(Direction.UP, 1), Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, directionMain.getOpposite()).setValue(BlockStateProperties.POWERED, true));

        // Four redstone dusts
        BlockState redstoneDustPlus = Blocks.REDSTONE_WIRE.defaultBlockState()
                .setValue(BlockStateProperties.EAST_REDSTONE, RedstoneSide.SIDE)
                .setValue(BlockStateProperties.WEST_REDSTONE, RedstoneSide.SIDE)
                .setValue(BlockStateProperties.NORTH_REDSTONE, RedstoneSide.SIDE)
                .setValue(BlockStateProperties.SOUTH_REDSTONE, RedstoneSide.SIDE);
        world.setBlockAndUpdate(startPos.relative(directionMain, 0).relative(directionSide, 0).relative(Direction.UP, 1), redstoneDustPlus.setValue(BlockStateProperties.POWER, 2));
        world.setBlockAndUpdate(startPos.relative(directionMain, 0).relative(directionSide, 2).relative(Direction.UP, 2), redstoneDustPlus);
        world.setBlockAndUpdate(startPos.relative(directionMain, 5).relative(directionSide, 0).relative(Direction.UP, 1), redstoneDustPlus);
        world.setBlockAndUpdate(startPos.relative(directionMain, 5).relative(directionSide, 2).relative(Direction.UP, 2), redstoneDustPlus);

        // Two redstone blocks
        world.setBlockAndUpdate(startPos.relative(directionMain, 3).relative(directionSide, 0).relative(Direction.UP, 1), Blocks.REDSTONE_BLOCK.defaultBlockState());
        world.setBlockAndUpdate(startPos.relative(directionMain, 3).relative(directionSide, 2).relative(Direction.UP, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());

        // Dropper (41 sticks)
        world.setBlockAndUpdate(startPos.relative(directionMain, 2).relative(directionSide, 1).relative(Direction.UP, 1), Blocks.DROPPER.defaultBlockState().setValue(BlockStateProperties.FACING, directionMain));
        DropperBlockEntity dropper = (DropperBlockEntity) world.getBlockEntity(startPos.relative(directionMain, 2).relative(directionSide, 1).relative(Direction.UP, 1));
        if (dropper != null) {
            ItemStack sticks41 = Items.STICK.getDefaultInstance();
            sticks41.setCount(41);
            dropper.setItem(0, sticks41);
            dropper.setChanged();
        }

        // Hopper (4 stacks + 49 sticks)
        world.setBlockAndUpdate(startPos.relative(directionMain, 3).relative(directionSide, 2).relative(Direction.UP, 1), Blocks.HOPPER.defaultBlockState().setValue(BlockStateProperties.FACING_HOPPER, directionMain.getOpposite()));
        world.setBlockAndUpdate(startPos.relative(directionMain, 3).relative(directionSide, 2).relative(Direction.UP, 1), Blocks.HOPPER.defaultBlockState().setValue(BlockStateProperties.FACING_HOPPER, directionMain.getOpposite())); // invisible block bug????
        HopperBlockEntity hopper = (HopperBlockEntity) world.getBlockEntity(startPos.relative(directionMain, 3).relative(directionSide, 2).relative(Direction.UP, 1));
        if (hopper != null) {
            ItemStack sticks64 = Items.STICK.getDefaultInstance();
            sticks64.setCount(64);
            ItemStack sticks49 = Items.STICK.getDefaultInstance();
            sticks49.setCount(49);
            hopper.setItem(0, sticks64.copy());
            hopper.setItem(1, sticks64.copy());
            hopper.setItem(2, sticks64.copy());
            hopper.setItem(3, sticks64.copy());
            hopper.setItem(4, sticks49);
            hopper.setChanged();
        }

        // The other two hoppers (empty)
        world.setBlockAndUpdate(startPos.relative(directionMain, 3).relative(directionSide, 1).relative(Direction.UP, 1), Blocks.HOPPER.defaultBlockState().setValue(BlockStateProperties.FACING_HOPPER, directionMain.getOpposite()));
        world.setBlockAndUpdate(startPos.relative(directionMain, 3).relative(directionSide, 1).relative(Direction.UP, 1), Blocks.HOPPER.defaultBlockState().setValue(BlockStateProperties.FACING_HOPPER, directionMain.getOpposite())); // invisible block bug????
        world.setBlockAndUpdate(startPos.relative(directionMain, 2).relative(directionSide, 2).relative(Direction.UP, 1), Blocks.HOPPER.defaultBlockState().setValue(BlockStateProperties.FACING_HOPPER, directionMain));

        // Four comparators
        world.setBlockAndUpdate(startPos.relative(directionMain, 1).relative(directionSide, 1).relative(Direction.UP, 1), Blocks.COMPARATOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, directionMain));
        world.setBlockAndUpdate(startPos.relative(directionMain, 1).relative(directionSide, 2).relative(Direction.UP, 1), Blocks.COMPARATOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, directionMain));
        world.setBlockAndUpdate(startPos.relative(directionMain, 4).relative(directionSide, 1).relative(Direction.UP, 1), Blocks.COMPARATOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, directionMain.getOpposite()));
        world.setBlockAndUpdate(startPos.relative(directionMain, 4).relative(directionSide, 2).relative(Direction.UP, 1), Blocks.COMPARATOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, directionMain.getOpposite()));

        // Note block
        world.setBlockAndUpdate(startPos.relative(directionMain, 2).relative(directionSide, 1).relative(Direction.UP, 2), Blocks.NOTE_BLOCK.defaultBlockState());

        // Four sticky pistons
        world.setBlockAndUpdate(startPos.relative(directionMain, 1).relative(directionSide, 0).relative(Direction.UP, 1), Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, directionMain));
        world.setBlockAndUpdate(startPos.relative(directionMain, 4).relative(directionSide, 0).relative(Direction.UP, 1), Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, directionMain.getOpposite()));
        world.setBlockAndUpdate(startPos.relative(directionMain, 1).relative(directionSide, 2).relative(Direction.UP, 2), Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, directionMain));
        world.setBlockAndUpdate(startPos.relative(directionMain, 4).relative(directionSide, 2).relative(Direction.UP, 2), Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, directionMain.getOpposite()));

        // Torch gotta be last
        BlockPos torchPos = startPos.relative(directionMain, -1);
        world.setBlockAndUpdate(torchPos, Blocks.REDSTONE_WALL_TORCH.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, directionMain.getOpposite()).setValue(BlockStateProperties.LIT, false));
        return torchPos;
    }

    /*
     * A little kludge to avoid having to pass the "player" around all the time;
     * instead we rely on the caller setting it before calling methods on us.
     * We use a weak reference, so we don't keep the player around (we don't own it).
     */

    private WeakReference<ServerPlayer> player;

    public void setPlayerEntity(ServerPlayer player) {
        if (this.player == null || this.player.get() != player) this.player = new WeakReference<>(player);
    }

    private void sendCommandFeedback(Component message) {
        ServerPlayer serverPlayerEntity = player.get();
        if (serverPlayerEntity == null) {
            LOGGER.error("Player went away????");
            return;
        }
        serverPlayerEntity.sendSystemMessage(message);
    }

    private void sendCommandFeedback(String format, Object... args) {
        sendCommandFeedback(Component.nullToEmpty(String.format(format, args)));
    }
}
