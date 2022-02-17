package pl.kosma.geodesy;

import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.function.Consumer;

class IterableBlockBox extends BlockBox {

    public IterableBlockBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        super(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public IterableBlockBox(BlockBox box) {
        this(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
    }

    public void forEachPosition(Consumer<BlockPos> consumer) {
        for (int x = this.getMinX(); x <= this.getMaxX(); x++)
            for (int y = this.getMinY(); y <= this.getMaxY(); y++)
                for (int z = this.getMinZ(); z <= this.getMaxZ(); z++)
                    consumer.accept(new BlockPos(x, y, z));
    }

    public void forEachWallPosition(Consumer<BlockPos> consumer) {
        this.forEachPosition(blockPos -> {
            if (blockPos.getX() == this.getMinX() || blockPos.getX() == this.getMaxX() ||
                    blockPos.getY() == this.getMinY() || blockPos.getY() == this.getMaxY() ||
                    blockPos.getZ() == this.getMinZ() || blockPos.getZ() == this.getMaxZ()) {
                consumer.accept(blockPos);
            }
        });
    }

    public void forEachEdgePosition(Consumer<BlockPos> consumer) {
        this.forEachPosition(blockPos -> {
            int count = 0;
            if (blockPos.getX() == this.getMinX() || blockPos.getX() == this.getMaxX())
                count++;
            if (blockPos.getY() == this.getMinY() || blockPos.getY() == this.getMaxY())
                count++;
            if (blockPos.getZ() == this.getMinZ() || blockPos.getZ() == this.getMaxZ())
                count++;
            if (count >= 2) {
                consumer.accept(blockPos);
            }
        });
    }

    public void slice(Direction.Axis axis, Consumer<IterableBlockBox> consumer) {
        switch (axis) {
            case X -> {
                for (int y = this.getMinY(); y <= this.getMaxY(); y++)
                    for (int z = this.getMinZ(); z <= this.getMaxZ(); z++)
                        consumer.accept(new IterableBlockBox(this.getMinX(), y, z, this.getMaxX(), y, z));
            }
            case Y -> {
                for (int x = this.getMinX(); x <= this.getMaxX(); x++)
                    for (int z = this.getMinZ(); z <= this.getMaxZ(); z++)
                        consumer.accept(new IterableBlockBox(x, this.getMinY(), z, x, this.getMaxY(), z));
            }
            case Z -> {
                for (int x = this.getMinX(); x <= this.getMaxX(); x++)
                    for (int y = this.getMinY(); y <= this.getMaxY(); y++)
                        consumer.accept(new IterableBlockBox(x, y, this.getMinZ(), x, y, this.getMaxZ()));
            }
        }
    }

    private int getX() {
        if (this.getMinX() != this.getMaxX())
            throw(new RuntimeException("non-noodle bounding box"));
        return this.getMinX();
    }

    private int getY() {
        if (this.getMinY() != this.getMaxY())
            throw(new RuntimeException("non-noodle bounding box"));
        return this.getMinY();
    }

    private int getZ() {
        if (this.getMinZ() != this.getMaxZ())
            throw(new RuntimeException("non-noodle bounding box"));
        return this.getMinZ();
    }

    public BlockPos getEndpoint(Direction direction) {
        return switch (direction) {
            case WEST  -> new BlockPos(this.getMinX(), this.getY(), this.getZ());
            case EAST  -> new BlockPos(this.getMaxX(), this.getY(), this.getZ());
            case DOWN  -> new BlockPos(this.getX(), this.getMinY(), this.getZ());
            case UP    -> new BlockPos(this.getX(), this.getMaxY(), this.getZ());
            case NORTH -> new BlockPos(this.getX(), this.getY(), this.getMinZ());
            case SOUTH -> new BlockPos(this.getX(), this.getY(), this.getMaxZ());
        };
    }

    // Backported from 1.18 because in 1.17 this function mutates state.
    public BlockBox expand(int offset) {
        return new BlockBox(this.getMinX() - offset, this.getMinY() - offset, this.getMinZ() - offset, this.getMaxX() + offset, this.getMaxY() + offset, this.getMaxZ() + offset);
    }
}
