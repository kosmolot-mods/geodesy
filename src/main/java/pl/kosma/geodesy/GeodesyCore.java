package pl.kosma.geodesy;

import com.google.common.collect.Sets;
import net.minecraft.block.*;
import net.minecraft.block.entity.CommandBlockBlockEntity;
import net.minecraft.block.entity.DropperBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.StructureBlockBlockEntity;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.StructureBlockMode;
import net.minecraft.block.enums.WallMountLocation;
import net.minecraft.block.enums.WireConnection;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static net.minecraft.block.Block.NOTIFY_LISTENERS;

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

    private ServerWorld world;
    @Nullable
    private IterableBlockBox geode;
    // The following list must contain all budding amethyst in the area.
    @Nullable
    private List<BlockPos> buddingAmethystPositions;
    @Nullable
    // The following list must contain all amethyst clusters in the area.
    private List<Pair<BlockPos, Direction>> amethystClusterPositions;

    public void geodesyGeodesy() {
        sendCommandFeedback("Welcome to Geodesy!");
        sendCommandFeedback("Read the book for all the gory details.");
        fillHotbar();
    }

    private void fillHotbar() {
        ServerPlayerEntity player = this.player.get();
        if (player == null)
            return;
        player.getInventory().setStack(0, UnholyBookOfGeodesy.summonKrivbeknih());
        player.getInventory().setStack(1, Items.SLIME_BLOCK.getDefaultStack());
        player.getInventory().setStack(2, Items.HONEY_BLOCK.getDefaultStack());
        player.getInventory().setStack(3, Items.WITHER_SKELETON_SKULL.getDefaultStack());
        player.getInventory().setStack(4, Items.ZOMBIE_HEAD.getDefaultStack());
        player.getInventory().setStack(5, Items.AIR.getDefaultStack());
        player.getInventory().setStack(6, Items.AIR.getDefaultStack());
        player.getInventory().setStack(7, Items.AIR.getDefaultStack());
        player.getInventory().setStack(8, Items.POISONOUS_POTATO.getDefaultStack());
    }

    void geodesyArea(ServerWorld world, BlockPos startPos, BlockPos endPos) {
        sendCommandFeedback("---");

        this.world = world;

        // Detect the geode area.
        detectGeode(startPos, endPos);
        if (geode != null) {
            prepareWorkArea(true);
            countClusters();
            highlightGeode();
        }
    }

    void geodesyProject(Direction[] directions) {
        // Return if geodesy area has not been run yet.
        if (geode == null || buddingAmethystPositions == null || amethystClusterPositions == null) {
            sendCommandFeedback("No area to analyze. Select an area with /geodesy area first.");
            return;
        }

        // Expand the area and clear it out for work purposes.
        this.prepareWorkArea(true);

        // Grow and count all amethyst (for efficiency calculation).
        this.growClusters();

        // Render a frame.
        IterableBlockBox frameBoundingBox = new IterableBlockBox(geode.expand(WALL_OFFSET));
        frameBoundingBox.forEachEdgePosition(blockPos -> world.setBlockState(blockPos, Blocks.MOSS_BLOCK.getDefaultState(), NOTIFY_LISTENERS));

        // Do nothing more if we have no directions - this signifies we just want
        // to draw the frame and do nothing else.
        if (directions == null)
            return;


        // Run the projection.
        for (Direction direction: directions) {
            this.projectGeode(direction);
        }

        // Replace all remaining amethyst clusters with buttons so items can't
        // fall on them and get stuck.
        AtomicInteger clustersLeft = new AtomicInteger();
        amethystClusterPositions.forEach(blockPosDirectionPair -> {
            if (world.getBlockState(blockPosDirectionPair.getLeft()).getBlock() == Blocks.AMETHYST_CLUSTER) {
                clustersLeft.getAndIncrement();
                world.setBlockState(blockPosDirectionPair.getLeft(), switch (blockPosDirectionPair.getRight()) {
                    case DOWN -> Blocks.SPRUCE_BUTTON.getDefaultState().with(Properties.WALL_MOUNT_LOCATION, WallMountLocation.CEILING);
                    case UP -> Blocks.SPRUCE_BUTTON.getDefaultState().with(Properties.WALL_MOUNT_LOCATION, WallMountLocation.FLOOR);
                    default -> Blocks.SPRUCE_BUTTON.getDefaultState().with(Properties.HORIZONTAL_FACING, blockPosDirectionPair.getRight());
                }, NOTIFY_LISTENERS);
            }
        });
        int clustersCollected = amethystClusterPositions.size() - clustersLeft.get();

        // Re-grow the buds so they are visible.
        this.growClusters();

        // Calculate and show layout efficiency.
        float efficiency = 100f * (clustersCollected) / amethystClusterPositions.size();
        String layoutName = String.join(" ", Arrays.stream(directions).map(Direction::toString).collect(Collectors.joining(" ")));
        sendCommandFeedback(" %s: %d%% (%d/%d)", layoutName, (int) efficiency, clustersCollected, amethystClusterPositions.size());
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

    void geodesyAssemble() {
        // Return if geodesy area has not been run yet.
        if (geode == null) {
            sendCommandFeedback("No area to analyze. Select an area with /geodesy area first.");
            return;
        }

        // Plop the clock at the top
        BlockPos clockPos = new BlockPos((geode.getMinX()+geode.getMaxX())/2+3, geode.getMaxY()+CLOCK_Y_OFFSET, (geode.getMinZ()+geode.getMaxZ())/2+1);
        BlockPos torchPos = buildClock(clockPos, Direction.WEST, Direction.NORTH);

        // Run along all the axes and move all slime/honey blocks inside the frame.
        for (Direction direction: Direction.values()) {
            geode.slice(direction.getAxis(), slice -> {
                // Calculate positions of the source and target blocks for moving.
                BlockPos targetPos = slice.getEndpoint(direction).offset(direction, WALL_OFFSET);
                BlockPos sourcePos = targetPos.offset(direction, 1);
                Block sourceBlock = world.getBlockState(sourcePos).getBlock();
                // Check that the operation can succeed.
                if (STICKY_BLOCKS.contains(sourceBlock)) {
                    world.setBlockState(targetPos, world.getBlockState(sourcePos), NOTIFY_LISTENERS);
                    world.setBlockState(sourcePos, Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
                }
            });
        }

        // Check each slice for a marker block.
        for (Direction slicingDirection: Direction.values()) {
            List<BlockPos> triggerObserverPositions = new ArrayList<>();
            geode.slice(slicingDirection.getAxis(), slice -> {
                // Check for blocker marker block.
                BlockPos blockerPos = slice.getEndpoint(slicingDirection).offset(slicingDirection, WALL_OFFSET + 2);
                BlockPos oppositeWallPos = slice.getEndpoint(slicingDirection.getOpposite()).offset(slicingDirection, -WALL_OFFSET);
                if (!MARKERS_BLOCKER.contains(world.getBlockState(blockerPos).getBlock()))
                    return;
                // Find the position of the first machine block.
                BlockPos firstMachinePos = null;
                for (Direction direction : Direction.values()) {
                    if (MARKERS_MACHINE.contains(world.getBlockState(blockerPos.offset(direction)).getBlock())) {
                        firstMachinePos = blockerPos.offset(direction);
                        break;
                    }
                }
                if (firstMachinePos == null)
                    return;
                // First the direction of the second (and third) machine block.
                Direction machineDirection = null;
                for (Direction direction : Direction.values()) {
                    if (MARKERS_MACHINE.contains(world.getBlockState(firstMachinePos.offset(direction, 1)).getBlock()) &&
                            MARKERS_MACHINE.contains(world.getBlockState(firstMachinePos.offset(direction, 2)).getBlock())) {
                        machineDirection = direction;
                        break;
                    }
                }
                if (machineDirection == null)
                    return;
                // Read the sticky block at machine position (we need its opposite).
                BlockPos stickyPos = slice.getEndpoint(slicingDirection).offset(slicingDirection, WALL_OFFSET);
                Block stickyBlock = world.getBlockState(stickyPos).getBlock();
                if (stickyBlock == Blocks.SLIME_BLOCK)
                    stickyBlock = Blocks.HONEY_BLOCK;
                else if (stickyBlock == Blocks.HONEY_BLOCK)
                    stickyBlock = Blocks.SLIME_BLOCK;
                else
                    return;
                // Important: the actual machine is built one block closer to the geode
                // than the player-placed markers are. Also wipe out the blocker marker because
                // it doesn't get removed otherwise.
                world.setBlockState(blockerPos, Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
                blockerPos = blockerPos.offset(slicingDirection.getOpposite());
                firstMachinePos = firstMachinePos.offset(slicingDirection.getOpposite());
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
        IterableBlockBox wallsBox = new IterableBlockBox(geode.expand(WALL_OFFSET));
        buildWalls(wallsBox);

        // Generate the water collection system.
        WaterCollectionSystemGenerator.generate(world, geode.expand(WALL_OFFSET - 1));
    }

    private void buildTriggerWiringHorizontal(List<BlockPos> observerPositions, Direction slicingDirection) {
        // Skip the wiring if there are no observers.
        if (observerPositions.isEmpty())
            return;

        // Calculate the volume containing the trigger observers.
        BlockBox observersVolume = BlockBox.encompassPositions(observerPositions).orElseThrow();

        // Calculate the lower edge of the trigger volume (shaped 1x1xN blocks).
        IterableBlockBox triggerVolumeLowerEdge = new IterableBlockBox(
                observersVolume.getMinX(), observersVolume.getMinY(), observersVolume.getMinZ(),
                observersVolume.getMaxX(), observersVolume.getMinY(), observersVolume.getMaxZ());

        // Build the trigger wiring along the lower edge.
        triggerVolumeLowerEdge.forEachPosition(triggerPos -> {
            // Calculate the 1x1 vertical box that encompasses all the possible observer positions in the current slice.
            IterableBlockBox observersBox = new IterableBlockBox(
                triggerPos.getX(), observersVolume.getMinY(), triggerPos.getZ(),
                triggerPos.getX(), observersVolume.getMaxY(), triggerPos.getZ());

            // Create a list of all needed scaffolding positions in this slice.
            List<BlockPos> scaffoldingPositions = observerPositions.stream()
                    // Extract all the observers belonging to the current slice
                    .filter(blockPos -> observersBox.contains(blockPos))
                    // Offset them all one block out, to become needed scaffolding positions
                    .map(blockPos -> blockPos.offset(slicingDirection))
                    .collect(Collectors.toList());
            if (!scaffoldingPositions.isEmpty()) {
                // Add the bottom scaffolding, which is always needed no matter what.
                scaffoldingPositions.add(triggerPos.offset(slicingDirection));
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
            world.setBlockState(triggerPos.offset(slicingDirection, 3).offset(Direction.UP, -2), FULL_BLOCK.getDefaultState(), NOTIFY_LISTENERS);
            world.setBlockState(triggerPos.offset(slicingDirection, 3).offset(Direction.UP, -1), Blocks.REDSTONE_WIRE.getDefaultState(), NOTIFY_LISTENERS);

            // The rest of wiring only applies if there are any observers present.
            if (!scaffoldingPositions.isEmpty()) {
                world.setBlockState(triggerPos.offset(slicingDirection, 1).offset(Direction.UP, -1), Blocks.IRON_TRAPDOOR.getDefaultState().with(TrapdoorBlock.FACING, slicingDirection).with(TrapdoorBlock.HALF, BlockHalf.TOP), NOTIFY_LISTENERS);
                world.setBlockState(triggerPos.offset(slicingDirection, 2).offset(Direction.UP, -1), Blocks.TARGET.getDefaultState(), NOTIFY_LISTENERS);
                world.setBlockState(triggerPos.offset(slicingDirection, 2).offset(Direction.UP, 0), Blocks.SCAFFOLDING.getDefaultState().with(ScaffoldingBlock.DISTANCE, 0), NOTIFY_LISTENERS);
                IterableBlockBox scaffoldingBox = new IterableBlockBox(BlockBox.encompassPositions(scaffoldingPositions).orElseThrow());
                scaffoldingBox.forEachPosition(scaffoldingPos -> {
                    world.setBlockState(scaffoldingPos, Blocks.SCAFFOLDING.getDefaultState().with(ScaffoldingBlock.DISTANCE, 0), NOTIFY_LISTENERS);
                });
            }
        });

        // Place all the observers last, so they don't trigger.
        observerPositions.forEach(observerPos -> {
            world.setBlockState(observerPos, Blocks.OBSERVER.getDefaultState().with(Properties.FACING, slicingDirection), NOTIFY_LISTENERS);
        });
    }

    private void buildTriggerWiringUp(List<BlockPos> observerPositions, BlockPos torchPos) {
        /*
         * On the top, the trigger wiring is a simple X-Y grid of redstone dust:
         * 1. On the X axis, there is a line running across the entire trigger area up to the torch.
              This line is on the Z coordinate of the torch.
         * 2. On the Z axis, there are lines running from each trigger point down to the center line.
         */

        List<IterableBlockBox> lines = new ArrayList<>();

        // The Z lines are per-observer.
        observerPositions.forEach(blockPos -> {
            lines.add(new IterableBlockBox(
                blockPos.getX(), blockPos.getY(), blockPos.getZ(),
                blockPos.getX(), blockPos.getY(), torchPos.getZ()));
        });

        // The X axis line needs to connect to the trigger position area - add it.
        // It's okay to modify it here, as no other code uses it.
        observerPositions.add(torchPos.offset(Direction.DOWN, 2));
        IterableBlockBox xLineArea = new IterableBlockBox(BlockBox.encompassPositions(observerPositions).orElseThrow());
        IterableBlockBox xLine = new IterableBlockBox(
            xLineArea.getMinX(), xLineArea.getMinY(), torchPos.getZ(),
            xLineArea.getMaxX(), xLineArea.getMinY(), torchPos.getZ());
        lines.add(xLine);

        // Create all the lines with redstone dust on top.
        lines.forEach(blockBox -> {
            blockBox.forEachPosition(blockPos -> {
                // Place a solid block if it's not already there.
                if (world.getBlockState(blockPos).getBlock() == Blocks.AIR)
                    world.setBlockState(blockPos, FULL_BLOCK.getDefaultState());
                // Place redstone dust on top if it's not already there.
                if (world.getBlockState(blockPos.offset(Direction.UP)).getBlock() == Blocks.AIR)
                    world.setBlockState(blockPos.offset(Direction.UP), Blocks.REDSTONE_WIRE.getDefaultState());
            });
        });
    }

    private void buildWalls(IterableBlockBox wallsBox) {
        for (Direction slicingDirection: Direction.values()) {
            // Top wall (lid) is transparent but we still run the processing
            // to remove all blocks that should be removed.
            BlockState wallBlock = (slicingDirection == Direction.UP) ?
                    Blocks.AIR.getDefaultState() :
                    Blocks.MOSS_BLOCK.getDefaultState();
            wallsBox.slice(slicingDirection.getAxis(), iterableBlockBox -> {
                BlockPos end = iterableBlockBox.getEndpoint(slicingDirection);
                if (!PRESERVE_WALL_BLOCKS.contains(world.getBlockState(end).getBlock()))
                    world.setBlockState(end, wallBlock);
            });
        }
    }

    private void prepareWorkArea(boolean force) {
        IterableBlockBox workBoundingBox = new IterableBlockBox(geode.expand(BUILD_MARGIN));
        BlockPos commandBlockPos = new BlockPos(workBoundingBox.getMaxX(), workBoundingBox.getMaxY(), workBoundingBox.getMaxZ());

        // Check for existing command block, bail out if found.
        if (!force) {
            if (world.getBlockState(commandBlockPos).getBlock() == Blocks.COMMAND_BLOCK)
                return;
        }

        // Wipe out the area (except stuff we preserve)
        workBoundingBox.forEachPosition(blockPos -> {
            if (!PRESERVE_BLOCKS.contains(world.getBlockState(blockPos).getBlock()))
                world.setBlockState(blockPos, Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
        });

        // Place walls inside to prevent water and falling blocks from going bonkers.
        IterableBlockBox wallsBoundingBox = new IterableBlockBox(workBoundingBox.expand(1));
        wallsBoundingBox.forEachWallPosition(blockPos -> {
            if (!PRESERVE_BLOCKS.contains(world.getBlockState(blockPos).getBlock()))
                world.setBlockState(blockPos, WORK_AREA_WALL.getDefaultState(), NOTIFY_LISTENERS);
        });

        // Add a command block to allow the player to reeexecute the command easily.
        String resumeCommand = String.format("/geodesy area %d %d %d %d %d %d",
                geode.getMinX(), geode.getMinY(), geode.getMinZ(), geode.getMaxX(), geode.getMaxY(), geode.getMaxZ());

        world.setBlockState(commandBlockPos, Blocks.COMMAND_BLOCK.getDefaultState(), NOTIFY_LISTENERS);
        CommandBlockBlockEntity commandBlock = (CommandBlockBlockEntity) world.getBlockEntity(commandBlockPos);
        if (commandBlock == null) {
            LOGGER.error("Command blocks are disabled on the server - unable to save the resume command.");
            return;
        }
        commandBlock.getCommandExecutor().setCommand(resumeCommand);
        commandBlock.markDirty();
    }

    private void detectGeode(BlockPos pos1, BlockPos pos2) {
        // Calculate the correct min/max coordinates and construct a box.
        IterableBlockBox scanBox = new IterableBlockBox(new BlockBox(
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
        geode = new IterableBlockBox(minX.get(), minY.get(), minZ.get(), maxX.get(), maxY.get(), maxZ.get()).expand(1);
    }

    private void highlightGeode() {
        // Highlight the geode area.
        int commandBlockOffset = WALL_OFFSET+1;
        BlockPos structureBlockPos = new BlockPos(geode.getMinX()-commandBlockOffset, geode.getMinY()-commandBlockOffset, geode.getMinZ()-commandBlockOffset);
        BlockState structureBlockState = Blocks.STRUCTURE_BLOCK.getDefaultState().with(StructureBlock.MODE, StructureBlockMode.SAVE);
        world.setBlockState(structureBlockPos, structureBlockState, NOTIFY_LISTENERS);
        StructureBlockBlockEntity structure = (StructureBlockBlockEntity) world.getBlockEntity(structureBlockPos);
        if (structure == null) {
            LOGGER.error("StructureBlock tile entity is missing... this should never happen????");
            return;
        }
        structure.setTemplateName("geodesy:geode");
        structure.setOffset(new BlockPos(commandBlockOffset, commandBlockOffset, commandBlockOffset));
        structure.setSize(geode.getDimensions().add(1, 1, 1));
        structure.setShowBoundingBox(true);
        structure.markDirty();
    }

    /**
     * Count all possible clusters from each budding block.
     */
    private void countClusters() {
        amethystClusterPositions = new ArrayList<>();
        buddingAmethystPositions.forEach(blockPos -> {
            for (Direction direction : Direction.values()) {
                BlockPos budPos = blockPos.offset(direction);
                if (world.getBlockState(budPos).getBlock() == Blocks.AIR) {
                    amethystClusterPositions.add(new Pair<>(budPos, direction));
                }
            }
        });
    }

    /**
     * Set all the clusters positions to cluster blocks if it is currently air.
     */
    private void growClusters() {
        amethystClusterPositions.forEach(blockPosDirectionPair -> {
            if (world.getBlockState(blockPosDirectionPair.getLeft()).getBlock() == Blocks.AIR) {
                world.setBlockState(blockPosDirectionPair.getLeft(), Blocks.AMETHYST_CLUSTER.getDefaultState().with(AmethystClusterBlock.FACING, blockPosDirectionPair.getRight()));
            }
        });
    }

    /**
     * Project the geode to a plane in a direction
     * Slices with budding amethyst(s) are marked with crying obsidian.
     * Slices with amethyst cluster(s) that needs to be harvested are marked with pumpkin.
     * @param direction The direction to project the geode to.
     * @author Kosma Moczek, Kevinthegreat
     */
    private void projectGeode(Direction direction) {
        // Mark wall for slices with budding amethysts.
        buddingAmethystPositions.forEach(blockPos -> {
            BlockPos wallPos = getWallPos(blockPos, direction);
            world.setBlockState(wallPos, Blocks.CRYING_OBSIDIAN.getDefaultState(), NOTIFY_LISTENERS);
        });
        // Mark wall for slices that needs to be harvested and have no budding amethysts.
        amethystClusterPositions.forEach(blockPosDirectionPair -> {
            // Check if the cluster is harvested.
            if (world.getBlockState(blockPosDirectionPair.getLeft()).getBlock() == Blocks.AMETHYST_CLUSTER) {
                BlockPos wallPos = getWallPos(blockPosDirectionPair.getLeft(), direction);
                BlockPos oppositeWallPos = getWallPos(blockPosDirectionPair.getLeft(), direction.getOpposite());
                // Check if the slice is marked with budding amethyst.
                if (world.getBlockState(wallPos).getBlock() != Blocks.CRYING_OBSIDIAN) {
                    // Mark all clusters in the slice as harvested.
                    BlockPos.iterate(wallPos, oppositeWallPos).forEach(pos -> {
                        world.setBlockState(pos, Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
                    });
                    world.setBlockState(wallPos, Blocks.PUMPKIN.getDefaultState(), NOTIFY_LISTENERS);
                }
            }
        });
    }

    /**
     * Get the position on the wall (with wall offset) of the geode bounding box for a position in a direction.
     * @param blockPos The block position.
     * @param direction The direction.
     * @return The position on the wall (with wall offset).
     * @author Kevinthegreat
     */
    private BlockPos getWallPos(BlockPos blockPos, Direction direction) {
        return switch (direction) {
            case EAST -> new BlockPos(geode.getMaxX() + WALL_OFFSET, blockPos.getY(), blockPos.getZ());
            case WEST -> new BlockPos(geode.getMinX() - WALL_OFFSET, blockPos.getY(), blockPos.getZ());
            case UP -> new BlockPos(blockPos.getX(), geode.getMaxY() + WALL_OFFSET, blockPos.getZ());
            case DOWN -> new BlockPos(blockPos.getX(), geode.getMinY() - WALL_OFFSET, blockPos.getZ());
            case SOUTH -> new BlockPos(blockPos.getX(), blockPos.getY(), geode.getMaxZ() + WALL_OFFSET);
            case NORTH -> new BlockPos(blockPos.getX(), blockPos.getY(), geode.getMinZ() - WALL_OFFSET);
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
        world.setBlockState(blockerPos, Blocks.OBSIDIAN.getDefaultState(), NOTIFY_LISTENERS);
        // Clear out the machine marker blocks.
        world.setBlockState(pos.offset(directionUp, 0), Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 1), Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 2), Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
        pos = pos.offset(directionAlong, 1);
        // First layer: piston, 2 slime
        world.setBlockState(pos.offset(directionUp, 0), Blocks.STICKY_PISTON.getDefaultState().with(Properties.FACING, directionAlong.getOpposite()), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 1), stickyBlock.getDefaultState(), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 2), stickyBlock.getDefaultState(), NOTIFY_LISTENERS);
        pos = pos.offset(directionAlong, 1);
        // Second layer: redstone lamp, observer, slime (order is important)
        world.setBlockState(pos.offset(directionUp, 2), stickyBlock.getDefaultState(), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 1), Blocks.OBSERVER.getDefaultState().with(Properties.FACING, directionUp), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 0), Blocks.REDSTONE_LAMP.getDefaultState(), NOTIFY_LISTENERS);
        pos = pos.offset(directionAlong, 1);
        // Third layer: observer, slime, slime
        world.setBlockState(pos.offset(directionUp, 0), Blocks.OBSERVER.getDefaultState().with(Properties.FACING, directionAlong.getOpposite()), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 1), stickyBlock.getDefaultState(), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 2), stickyBlock.getDefaultState(), NOTIFY_LISTENERS);
        pos = pos.offset(directionAlong, 1);
        // Fourth layer: piston, slime
        world.setBlockState(pos.offset(directionUp, 0), Blocks.STICKY_PISTON.getDefaultState().with(Properties.FACING, directionAlong), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 1), stickyBlock.getDefaultState(), NOTIFY_LISTENERS);
        pos = pos.offset(directionAlong, 1);
        // Fifth layer: slime, piston
        world.setBlockState(pos.offset(directionUp, 0), Blocks.SLIME_BLOCK.getDefaultState(), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 1), Blocks.STICKY_PISTON.getDefaultState().with(Properties.FACING, directionAlong.getOpposite()), NOTIFY_LISTENERS);
        pos = pos.offset(directionAlong, 2);
        // [SKIP!] Seventh layer: slime, note block
        world.setBlockState(pos.offset(directionUp, 0), Blocks.SLIME_BLOCK.getDefaultState(), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 1), Blocks.NOTE_BLOCK.getDefaultState(), NOTIFY_LISTENERS);
        pos = pos.offset(directionAlong, -1);
        // [GO BACK!] Sixth layer: slime, observer
        // This one is tricky, we initially set the observer in a wrong direction
        // so the note block tune change is not triggered.
        world.setBlockState(pos.offset(directionUp, 1), Blocks.OBSERVER.getDefaultState().with(Properties.FACING, directionUp), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 0), Blocks.SLIME_BLOCK.getDefaultState(), NOTIFY_LISTENERS);
        world.setBlockState(pos.offset(directionUp, 1), Blocks.OBSERVER.getDefaultState().with(Properties.FACING, directionAlong), NOTIFY_LISTENERS);
        pos = pos.offset(directionAlong, 2);
        // [SKIP AGAIN!] Eighth layer: blocker
        world.setBlockState(pos.offset(directionUp, 0), Blocks.OBSIDIAN.getDefaultState(), NOTIFY_LISTENERS);

        // Also blocker on the other end (the other wall).
        world.setBlockState(oppositeWallPos, Blocks.OBSIDIAN.getDefaultState(), NOTIFY_LISTENERS);

        // Return the position of the observer that can trigger the machine.
        // It will be used later (in separate logic) to create the trigger wiring.
        return pos.offset(directionUp, 1);
    }

    private BlockPos buildClock(BlockPos startPos, Direction directionMain, Direction directionSide) {
        // Platform
        for (int i=0; i<6; i++)
            for (int j=0; j<3; j++)
                world.setBlockState(startPos.offset(directionMain, i).offset(directionSide, j), FULL_BLOCK.getDefaultState());

        // Four solid blocks
        world.setBlockState(startPos.offset(directionMain, 0).offset(directionSide, 1).offset(Direction.UP, 1), FULL_BLOCK.getDefaultState());
        world.setBlockState(startPos.offset(directionMain, 0).offset(directionSide, 2).offset(Direction.UP, 1), FULL_BLOCK.getDefaultState());
        world.setBlockState(startPos.offset(directionMain, 5).offset(directionSide, 1).offset(Direction.UP, 1), FULL_BLOCK.getDefaultState());
        world.setBlockState(startPos.offset(directionMain, 5).offset(directionSide, 2).offset(Direction.UP, 1), FULL_BLOCK.getDefaultState());

        // Lever
        world.setBlockState(startPos.offset(directionMain, -1).offset(directionSide, 2).offset(Direction.UP, 1), Blocks.LEVER.getDefaultState().with(Properties.HORIZONTAL_FACING, directionMain.getOpposite()).with(Properties.POWERED, true));

        // Four redstone dusts
        BlockState redstoneDustPlus = Blocks.REDSTONE_WIRE.getDefaultState()
                .with(Properties.EAST_WIRE_CONNECTION, WireConnection.SIDE)
                .with(Properties.WEST_WIRE_CONNECTION, WireConnection.SIDE)
                .with(Properties.NORTH_WIRE_CONNECTION, WireConnection.SIDE)
                .with(Properties.SOUTH_WIRE_CONNECTION, WireConnection.SIDE);
        world.setBlockState(startPos.offset(directionMain, 0).offset(directionSide, 0).offset(Direction.UP, 1), redstoneDustPlus.with(Properties.POWER, 2));
        world.setBlockState(startPos.offset(directionMain, 0).offset(directionSide, 2).offset(Direction.UP, 2), redstoneDustPlus);
        world.setBlockState(startPos.offset(directionMain, 5).offset(directionSide, 0).offset(Direction.UP, 1), redstoneDustPlus);
        world.setBlockState(startPos.offset(directionMain, 5).offset(directionSide, 2).offset(Direction.UP, 2), redstoneDustPlus);

        // Two redstone blocks
        world.setBlockState(startPos.offset(directionMain, 3).offset(directionSide, 0).offset(Direction.UP, 1), Blocks.REDSTONE_BLOCK.getDefaultState());
        world.setBlockState(startPos.offset(directionMain, 3).offset(directionSide, 2).offset(Direction.UP, 2), Blocks.REDSTONE_BLOCK.getDefaultState());

        // Dropper (41 sticks)
        world.setBlockState(startPos.offset(directionMain, 2).offset(directionSide, 1).offset(Direction.UP, 1), Blocks.DROPPER.getDefaultState().with(Properties.FACING, directionMain));
        DropperBlockEntity dropper = (DropperBlockEntity) world.getBlockEntity(startPos.offset(directionMain, 2).offset(directionSide, 1).offset(Direction.UP, 1));
        if (dropper != null) {
            ItemStack sticks41 = Items.STICK.getDefaultStack();
            sticks41.setCount(41);
            dropper.setStack(0, sticks41);
            dropper.markDirty();
        }

        // Hopper (4 stacks + 49 sticks)
        world.setBlockState(startPos.offset(directionMain, 3).offset(directionSide, 2).offset(Direction.UP, 1), Blocks.HOPPER.getDefaultState().with(Properties.HOPPER_FACING, directionMain.getOpposite()));
        world.setBlockState(startPos.offset(directionMain, 3).offset(directionSide, 2).offset(Direction.UP, 1), Blocks.HOPPER.getDefaultState().with(Properties.HOPPER_FACING, directionMain.getOpposite())); // invisible block bug????
        HopperBlockEntity hopper = (HopperBlockEntity) world.getBlockEntity(startPos.offset(directionMain, 3).offset(directionSide, 2).offset(Direction.UP, 1));
        if (hopper != null) {
            ItemStack sticks64 = Items.STICK.getDefaultStack();
            sticks64.setCount(64);
            ItemStack sticks49 = Items.STICK.getDefaultStack();
            sticks49.setCount(49);
            hopper.setStack(0, sticks64.copy());
            hopper.setStack(1, sticks64.copy());
            hopper.setStack(2, sticks64.copy());
            hopper.setStack(3, sticks64.copy());
            hopper.setStack(4, sticks49);
            hopper.markDirty();
        }

        // The other two hoppers (empty)
        world.setBlockState(startPos.offset(directionMain, 3).offset(directionSide, 1).offset(Direction.UP, 1), Blocks.HOPPER.getDefaultState().with(Properties.HOPPER_FACING, directionMain.getOpposite()));
        world.setBlockState(startPos.offset(directionMain, 3).offset(directionSide, 1).offset(Direction.UP, 1), Blocks.HOPPER.getDefaultState().with(Properties.HOPPER_FACING, directionMain.getOpposite())); // invisible block bug????
        world.setBlockState(startPos.offset(directionMain, 2).offset(directionSide, 2).offset(Direction.UP, 1), Blocks.HOPPER.getDefaultState().with(Properties.HOPPER_FACING, directionMain));

        // Four comparators
        world.setBlockState(startPos.offset(directionMain, 1).offset(directionSide, 1).offset(Direction.UP, 1), Blocks.COMPARATOR.getDefaultState().with(Properties.HORIZONTAL_FACING, directionMain));
        world.setBlockState(startPos.offset(directionMain, 1).offset(directionSide, 2).offset(Direction.UP, 1), Blocks.COMPARATOR.getDefaultState().with(Properties.HORIZONTAL_FACING, directionMain));
        world.setBlockState(startPos.offset(directionMain, 4).offset(directionSide, 1).offset(Direction.UP, 1), Blocks.COMPARATOR.getDefaultState().with(Properties.HORIZONTAL_FACING, directionMain.getOpposite()));
        world.setBlockState(startPos.offset(directionMain, 4).offset(directionSide, 2).offset(Direction.UP, 1), Blocks.COMPARATOR.getDefaultState().with(Properties.HORIZONTAL_FACING, directionMain.getOpposite()));

        // Note block
        world.setBlockState(startPos.offset(directionMain, 2).offset(directionSide, 1).offset(Direction.UP, 2), Blocks.NOTE_BLOCK.getDefaultState());

        // Four sticky pistons
        world.setBlockState(startPos.offset(directionMain, 1).offset(directionSide, 0).offset(Direction.UP, 1), Blocks.STICKY_PISTON.getDefaultState().with(Properties.FACING, directionMain));
        world.setBlockState(startPos.offset(directionMain, 4).offset(directionSide, 0).offset(Direction.UP, 1), Blocks.STICKY_PISTON.getDefaultState().with(Properties.FACING, directionMain.getOpposite()));
        world.setBlockState(startPos.offset(directionMain, 1).offset(directionSide, 2).offset(Direction.UP, 2), Blocks.STICKY_PISTON.getDefaultState().with(Properties.FACING, directionMain));
        world.setBlockState(startPos.offset(directionMain, 4).offset(directionSide, 2).offset(Direction.UP, 2), Blocks.STICKY_PISTON.getDefaultState().with(Properties.FACING, directionMain.getOpposite()));

        // Torch gotta be last
        BlockPos torchPos = startPos.offset(directionMain, -1);
        world.setBlockState(torchPos, Blocks.REDSTONE_WALL_TORCH.getDefaultState().with(Properties.HORIZONTAL_FACING, directionMain.getOpposite()).with(Properties.LIT, false));
        return torchPos;
    }

    /*
     * A little kludge to avoid having to pass the "player" around all the time;
     * instead we rely on the caller setting it before calling methods on us.
     * We use a weak reference, so we don't keep the player around (we don't own it).
     */

    private WeakReference<ServerPlayerEntity> player;

    public void setPlayerEntity(ServerPlayerEntity player) {
        this.player = new WeakReference<>(player);
    }

    private void sendCommandFeedback(Text message) {
        ServerPlayerEntity serverPlayerEntity = player.get();
        if (serverPlayerEntity == null) {
            LOGGER.error("Player went away????");
            return;
        }
        serverPlayerEntity.sendMessage(message);
    }

    private void sendCommandFeedback(String format, Object... args) {
        sendCommandFeedback(Text.of(String.format(format, args)));
    }
}
