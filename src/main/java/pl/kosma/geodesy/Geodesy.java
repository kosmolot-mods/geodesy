package pl.kosma.geodesy;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.block.AmethystClusterBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static net.minecraft.block.Block.NOTIFY_LISTENERS;
import static net.minecraft.server.command.CommandManager.*;

public class Geodesy implements ModInitializer {
    final int SCAN_RANGE = 16;
    final int BUILD_MARGIN = 11;
    final int WALL_OFFSET = 2;

    private class IterableBlockBox extends BlockBox {

        public IterableBlockBox(BlockPos pos) {
            super(pos);
        }

        public IterableBlockBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            super(minX, minY, minZ, maxX, maxY, maxZ);
        }

        public IterableBlockBox(BlockBox box) {
            this(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
        }

        public void iterate(Consumer<BlockPos> consumer) {
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
                            .then(argument("axis1", StringArgumentType.string())
                                    .then(argument("axis2", StringArgumentType.string()).executes(context -> {
                                                World world = context.getSource().getPlayer().getWorld();
                                                BlockPos startPos = BlockPosArgumentType.getBlockPos(context, "startPos");
                                                String axis1 = StringArgumentType.getString(context, "axis1");
                                                String axis2 = StringArgumentType.getString(context, "axis2");
                                                this.runGeodesy(world, startPos, Direction.Axis.fromName(axis1), Direction.Axis.fromName(axis2));
                                                return Command.SINGLE_SUCCESS;
                                            })
                                    ))));
        });
    }

    private void projectGeode(World world, IterableBlockBox geodeBoundingBox, Direction.Axis sliceAxis, Set<Direction.Axis> budAxes) {
        geodeBoundingBox.slice(sliceAxis, slice -> {
            // For each slice, determine the block composition
            AtomicBoolean hasBlock = new AtomicBoolean(false);
            AtomicBoolean hasCluster = new AtomicBoolean(false);
            slice.iterate(blockPos -> {
                BlockState blockState = world.getBlockState(blockPos);
                Block block = blockState.getBlock();
                if (block == Blocks.BUDDING_AMETHYST) {
                    hasBlock.set(true);
                }
                if (block == Blocks.AMETHYST_CLUSTER) {
                    // Clusters that go along the same axis as the slice are ignored
                    // since they have to be swept by a different axis machine anyway.
                    Direction clusterFacing = blockState.get(AmethystClusterBlock.FACING);
                    if (budAxes.contains(clusterFacing.getAxis()))
                        hasCluster.set(true);
                }
            });
            // Choose sidewall block type depending on the block composition on the slice
            Block wallBlock = Blocks.AIR;
            if (hasBlock.get() && hasCluster.get())
                wallBlock = Blocks.CRYING_OBSIDIAN;
            else if (hasBlock.get())
                wallBlock = Blocks.OBSIDIAN;
            else if (hasCluster.get())
                wallBlock = Blocks.SLIME_BLOCK;
            // Set sidewall block
            BlockPos wallPos = new BlockPos(slice.getMinX(), slice.getMinY(), slice.getMinZ()).offset(sliceAxis, -this.WALL_OFFSET);
            world.setBlockState(wallPos, wallBlock.getDefaultState());
        });
    }

    private void runGeodesy(World world, BlockPos startPos, Direction.Axis axis1, Direction.Axis axis2) {
        IterableBlockBox geodeBoundingBox = new IterableBlockBox(startPos);
        IterableBlockBox geodeScanBoundingBox = new IterableBlockBox(geodeBoundingBox.expand(this.SCAN_RANGE));

        // Scan the area to determine the extent of the geode.
        geodeScanBoundingBox.iterate(blockPos -> {
            if (world.getBlockState(blockPos).getBlock() == Blocks.BUDDING_AMETHYST)
                geodeBoundingBox.encompass(blockPos);
        });

        // Expand the area and clear it out for work purposes.
        IterableBlockBox workBoundingBox = new IterableBlockBox(geodeBoundingBox.expand(BUILD_MARGIN));
        workBoundingBox.iterate(blockPos -> {
            if (world.getBlockState(blockPos).getBlock() != Blocks.BUDDING_AMETHYST)
                world.setBlockState(blockPos, Blocks.AIR.getDefaultState(), NOTIFY_LISTENERS);
        });

        // Generate grown crystals in the working area.
        geodeBoundingBox.iterate(blockPos -> {
            if (world.getBlockState(blockPos).getBlock() == Blocks.BUDDING_AMETHYST) {
                for (Direction direction: Direction.values()) {
                    BlockPos budPos = blockPos.offset(direction);
                    if (world.getBlockState(budPos).getBlock() == Blocks.AIR)
                        world.setBlockState(budPos, Blocks.AMETHYST_CLUSTER.getDefaultState().with(AmethystClusterBlock.FACING, direction));
                }
            }
        });

        // Projection bounding box has to be one block bigger.
        IterableBlockBox projectionBoundingBox = new IterableBlockBox(geodeBoundingBox.expand(1));

        // Axis sets have been derived using pen and paper.
        Set<Direction.Axis> primaryAxes = new HashSet<>();
        primaryAxes.add(Direction.Axis.X);
        primaryAxes.add(Direction.Axis.Y);
        primaryAxes.add(Direction.Axis.Z);
        primaryAxes.remove(axis1);
        Set<Direction.Axis> secondaryAxes = new HashSet<>(List.of(axis1));
        // Run the projection.
        this.projectGeode(world, projectionBoundingBox, axis1, primaryAxes);
        this.projectGeode(world, projectionBoundingBox, axis2, secondaryAxes);
    }
}
