package pl.kosma.geodesy;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.block.AmethystClusterBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.StructureBlockBlockEntity;
import net.minecraft.block.enums.StructureBlockMode;
import net.minecraft.block.enums.WallMountLocation;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static net.minecraft.block.Block.NOTIFY_LISTENERS;
import static net.minecraft.server.command.CommandManager.*;

public class Geodesy implements ModInitializer {
    final int BUILD_MARGIN = 11;
    final int WALL_OFFSET = 2;

    private World world;
    private IterableBlockBox geode;

    private static class IterableBlockBox extends BlockBox {

        public IterableBlockBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            super(minX, minY, minZ, maxX, maxY, maxZ);
        }

        public IterableBlockBox(BlockBox box) {
            this(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
        }

        public void forEachPosition(Consumer<BlockPos> consumer) {
            for (int x=this.getMinX(); x<=this.getMaxX(); x++)
                for (int y=this.getMinY(); y<=this.getMaxY(); y++)
                    for (int z=this.getMinZ(); z<=this.getMaxZ(); z++)
                        consumer.accept(new BlockPos(x, y, z));
        }

        public void slice(Direction.Axis axis, Consumer<IterableBlockBox> consumer) {
            switch (axis) {
                case X -> {
                    for (int y=this.getMinY(); y<=this.getMaxY(); y++)
                        for (int z=this.getMinZ(); z<=this.getMaxZ(); z++)
                            consumer.accept(new IterableBlockBox(this.getMinX(), y, z, this.getMaxX(), y, z));
                }
                case Y -> {
                    for (int x=this.getMinX(); x<=this.getMaxX(); x++)
                        for (int z=this.getMinZ(); z<=this.getMaxZ(); z++)
                            consumer.accept(new IterableBlockBox(x, this.getMinY(), z, x, this.getMaxY(), z));
                }
                case Z -> {
                    for (int x=this.getMinX(); x<=this.getMaxX(); x++)
                        for (int y=this.getMinY(); y<=this.getMaxY(); y++)
                            consumer.accept(new IterableBlockBox(x, y, this.getMinZ(), x, y, this.getMaxZ()));
                }
            }
        }
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            dispatcher.register(literal("geodesy")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(literal("prepare")
                        .then(argument("startPos", BlockPosArgumentType.blockPos())
                            .then(argument("endPos", BlockPosArgumentType.blockPos())
                                .executes(context -> {
                                    World world = context.getSource().getPlayer().getWorld();
                                    BlockPos startPos = BlockPosArgumentType.getBlockPos(context, "startPos");
                                    BlockPos endPos = BlockPosArgumentType.getBlockPos(context, "endPos");
                                    this.geodesyPrepare(world, startPos, endPos);
                                    return Command.SINGLE_SUCCESS;
                                }))))
                    .then(literal("build")
                        .then(argument("axes", StringArgumentType.string())
                            .executes(context -> {
                                String axes = StringArgumentType.getString(context, "axes");
                                Direction.Axis[] axisList = new Direction.Axis[axes.length()];
                                Arrays.setAll(axisList, i -> Direction.Axis.fromName(axes.substring(i, i+1)));
                                this.geodesyBuild(axisList);
                                return Command.SINGLE_SUCCESS;
                            })))
                    .then(literal("collapse").executes(context -> {
                        this.geodesyCollapse();
                        return Command.SINGLE_SUCCESS;
                    }))
            );
        });
    }

    private void geodesyPrepare(World world, BlockPos startPos, BlockPos endPos) {
        this.world = world;

        // Detect the geode area.
        this.geode = detectGeode(world, startPos, endPos);

        // Expand the area and clear it out for work purposes.
        this.prepareWorkArea();
        this.growClusters();

        // Highlight the geode area.
        int offset = WALL_OFFSET+1;
        BlockPos structureBlockPos = new BlockPos(geode.getMinX()-offset, geode.getMinY()-offset, geode.getMinZ()-offset);
        world.setBlockState(structureBlockPos, Blocks.STRUCTURE_BLOCK.getDefaultState(), NOTIFY_LISTENERS);
        StructureBlockBlockEntity structure = (StructureBlockBlockEntity) world.getBlockEntity(structureBlockPos);
        structure.setMode(StructureBlockMode.SAVE);
        structure.setStructureName("geode");
        structure.setOffset(new BlockPos(offset, offset, offset));
        structure.setSize(geode.getDimensions().add(1, 1, 1));
        structure.setShowBoundingBox(true);
        structure.markDirty();
    }

    private void geodesyBuild(Direction.Axis[] axes) {
        // Clear the working area.
        this.prepareWorkArea();

        // Generate grown crystals in the working area.
        this.growClusters();

        // Run the projection.
        for (Direction.Axis axis: axes) {
            this.projectGeode(axis);
        }

        // Add a frame.
        IterableBlockBox frameBoundingBox = new IterableBlockBox(geode.expand(WALL_OFFSET));
        frameBoundingBox.forEachPosition(blockPos -> {
            // Add a wireframe of obsidian around the farm.
            int count = 0;
            if (blockPos.getX() == frameBoundingBox.getMinX() || blockPos.getX() == frameBoundingBox.getMaxX())
                count++;
            if (blockPos.getY() == frameBoundingBox.getMinY() || blockPos.getY() == frameBoundingBox.getMaxY())
                count++;
            if (blockPos.getZ() == frameBoundingBox.getMinZ() || blockPos.getZ() == frameBoundingBox.getMaxZ())
                count++;
            if (count >= 2) {
                world.setBlockState(blockPos, Blocks.OBSIDIAN.getDefaultState(), NOTIFY_LISTENERS);
            } else {
                // Opposite wall gets crying obsidian
                if (blockPos.getX() == frameBoundingBox.getMinX() ||
                        blockPos.getY() == frameBoundingBox.getMinY() ||
                        blockPos.getZ() == frameBoundingBox.getMinZ()) {
                    world.setBlockState(blockPos, Blocks.CRYING_OBSIDIAN.getDefaultState(), NOTIFY_LISTENERS);
                }
            }
        });

        // Replace all remaining amethyst clusters with buttons so items can't
        // fall on them and get stuck.
        frameBoundingBox.forEachPosition(blockPos -> {
            BlockState blockState = world.getBlockState(blockPos);
            if (blockState.getBlock() == Blocks.AMETHYST_CLUSTER) {
                BlockState button = Blocks.POLISHED_BLACKSTONE_BUTTON.getDefaultState();
                Direction facing = blockState.get(Properties.FACING);
                button = switch (facing) {
                    case DOWN -> button.with(Properties.WALL_MOUNT_LOCATION, WallMountLocation.CEILING);
                    case UP -> button.with(Properties.WALL_MOUNT_LOCATION, WallMountLocation.FLOOR);
                    default -> button.with(Properties.HORIZONTAL_FACING, facing);
                };
                world.setBlockState(blockPos, button, NOTIFY_LISTENERS);
            }
        });
    }

    private void geodesyCollapse() {
        // Run along all the axes and move all slime/honey blocks inside the frame.
        for (Direction.Axis axis: Direction.Axis.values()) {
            geode.slice(axis, slice -> {
                // Calculate positions of the source and target blocks for moving.
                BlockPos targetPos = new BlockPos(slice.getMaxX(), slice.getMaxY(), slice.getMaxZ()).offset(axis, WALL_OFFSET);
                BlockPos sourcePos = targetPos.offset(axis, 1);
                Block sourceBlock = world.getBlockState(sourcePos).getBlock();
                Block targetBlock = world.getBlockState(targetPos).getBlock();
                // Check that the operation can succeed.
                if (sourceBlock != Blocks.AIR && targetBlock != Blocks.CRYING_OBSIDIAN) {
                    world.setBlockState(targetPos, world.getBlockState(sourcePos), NOTIFY_LISTENERS);
                    world.setBlockState(sourcePos, Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
                }
            });
        }
    }

    private void prepareWorkArea() {
        IterableBlockBox workBoundingBox = new IterableBlockBox(geode.expand(BUILD_MARGIN));
        workBoundingBox.forEachPosition(blockPos -> {
            if (world.getBlockState(blockPos).getBlock() != Blocks.BUDDING_AMETHYST)
                world.setBlockState(blockPos, Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
        });
    }

    private IterableBlockBox detectGeode(World world, BlockPos pos1, BlockPos pos2) {
        // Calculate the correct min/max coordinates and construct a box.
        IterableBlockBox scanBox = new IterableBlockBox(new BlockBox(
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ()),
                Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ())
        ));

        // Scan the box, marking any positions with budding amethyst.
        List<BlockPos> amethystPositions = new ArrayList<>();
        scanBox.forEachPosition(blockPos -> {
            if (world.getBlockState(blockPos).getBlock() == Blocks.BUDDING_AMETHYST)
                amethystPositions.add(blockPos);
        });

        // Calculate the minimum bounding box that contains these positions.
        return new IterableBlockBox(BlockBox.encompassPositions(amethystPositions).orElse(null).expand(1));
    }

    private void growClusters() {
        geode.forEachPosition(blockPos -> {
            if (world.getBlockState(blockPos).getBlock() == Blocks.BUDDING_AMETHYST) {
                for (Direction direction: Direction.values()) {
                    BlockPos budPos = blockPos.offset(direction);
                    if (world.getBlockState(budPos).getBlock() == Blocks.AIR)
                        world.setBlockState(budPos, Blocks.AMETHYST_CLUSTER.getDefaultState().with(AmethystClusterBlock.FACING, direction));
                }
            }
        });
    }

    private void projectGeode(Direction.Axis axis) {
        geode.slice(axis, slice -> {
            // For each slice, determine the block composition
            AtomicBoolean hasBlock = new AtomicBoolean(false);
            AtomicBoolean hasCluster = new AtomicBoolean(false);
            slice.forEachPosition(blockPos -> {
                BlockState blockState = world.getBlockState(blockPos);
                Block block = blockState.getBlock();
                if (block == Blocks.BUDDING_AMETHYST)
                    hasBlock.set(true);
                if (block == Blocks.AMETHYST_CLUSTER)
                    hasCluster.set(true);
            });
            // Choose sidewall block type depending on the block composition on the slice
            Block wallBlock;
            if (hasBlock.get())
                wallBlock = Blocks.CRYING_OBSIDIAN;
            else if (hasCluster.get())
                wallBlock = Blocks.MOSS_BLOCK;
            else
                wallBlock = Blocks.AIR;
            // If this location has a flying machine, wipe out everything in that slice
            // to simulate the flying machine doing its work.
            if (wallBlock == Blocks.MOSS_BLOCK) {
                slice.forEachPosition(blockPos -> {
                    world.setBlockState(blockPos, Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
                });
            }
            // Set sidewall block
            BlockPos wallPos = new BlockPos(slice.getMaxX(), slice.getMaxY(), slice.getMaxZ()).offset(axis, this.WALL_OFFSET);
            world.setBlockState(wallPos, wallBlock.getDefaultState());
        });
    }
}
