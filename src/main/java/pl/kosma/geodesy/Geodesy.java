package pl.kosma.geodesy;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.block.AmethystClusterBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
                    .then(argument("startPos", BlockPosArgumentType.blockPos())
                            .then(argument("endPos", BlockPosArgumentType.blockPos())
                                    .then(argument("axes", StringArgumentType.string()).executes(context -> {
                                            World world = context.getSource().getPlayer().getWorld();
                                            BlockPos startPos = BlockPosArgumentType.getBlockPos(context, "startPos");
                                            BlockPos endPos = BlockPosArgumentType.getBlockPos(context, "endPos");
                                            String axes = StringArgumentType.getString(context, "axes");
                                            Direction.Axis[] axisList = new Direction.Axis[axes.length()];
                                            Arrays.setAll(axisList, i -> Direction.Axis.fromName(axes.substring(i, i+1)));
                                            this.runGeodesy(world, startPos, endPos, axisList);
                                            return Command.SINGLE_SUCCESS;
                                        })
            ))));
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

    private void growClusters(World world, IterableBlockBox geodeBoundingBox) {
        geodeBoundingBox.forEachPosition(blockPos -> {
            if (world.getBlockState(blockPos).getBlock() == Blocks.BUDDING_AMETHYST) {
                for (Direction direction: Direction.values()) {
                    BlockPos budPos = blockPos.offset(direction);
                    if (world.getBlockState(budPos).getBlock() == Blocks.AIR)
                        world.setBlockState(budPos, Blocks.AMETHYST_CLUSTER.getDefaultState().with(AmethystClusterBlock.FACING, direction));
                }
            }
        });
    }

    private void projectGeode(World world, IterableBlockBox geodeBoundingBox, Direction.Axis sliceAxis) {
        geodeBoundingBox.slice(sliceAxis, slice -> {
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
            BlockPos wallPos = new BlockPos(slice.getMaxX(), slice.getMaxY(), slice.getMaxZ()).offset(sliceAxis, this.WALL_OFFSET);
            world.setBlockState(wallPos, wallBlock.getDefaultState());
        });
    }

    private void runGeodesy(World world, BlockPos startPos, BlockPos endPos, Direction.Axis[] axes) {
        IterableBlockBox geodeBoundingBox = detectGeode(world, startPos, endPos);

        // Expand the area and clear it out for work purposes.
        IterableBlockBox workBoundingBox = new IterableBlockBox(geodeBoundingBox.expand(BUILD_MARGIN));
        workBoundingBox.forEachPosition(blockPos -> {
            if (world.getBlockState(blockPos).getBlock() != Blocks.BUDDING_AMETHYST)
                world.setBlockState(blockPos, Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
        });

        // Generate grown crystals in the working area.
        growClusters(world, geodeBoundingBox);

        // Run the projection.
        for (Direction.Axis axis: axes) {
            this.projectGeode(world, geodeBoundingBox, axis);
        }

        // Postprocess the area.
        IterableBlockBox frameBoundingBox = new IterableBlockBox(geodeBoundingBox.expand(WALL_OFFSET));
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
            // Replace all remaining amethyst clusters with buttons so items can't
            // fall on them and get stuck.
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
}
