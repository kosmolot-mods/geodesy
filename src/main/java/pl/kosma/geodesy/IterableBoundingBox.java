package pl.kosma.geodesy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.function.Consumer;

class IterableBoundingBox extends BoundingBox {

    public IterableBoundingBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        super(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public IterableBoundingBox(BoundingBox box) {
        this(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    public void forEachPosition(Consumer<BlockPos> consumer) {
        for (int x = this.minX(); x <= this.maxX(); x++)
            for (int y = this.minY(); y <= this.maxY(); y++)
                for (int z = this.minZ(); z <= this.maxZ(); z++)
                    consumer.accept(new BlockPos(x, y, z));
    }

    public void forEachWallPosition(Consumer<BlockPos> consumer) {
        this.forEachPosition(blockPos -> {
            if (blockPos.getX() == this.minX() || blockPos.getX() == this.maxX() ||
                    blockPos.getY() == this.minY() || blockPos.getY() == this.maxY() ||
                    blockPos.getZ() == this.minZ() || blockPos.getZ() == this.maxZ()) {
                consumer.accept(blockPos);
            }
        });
    }

    public void forEachEdgePosition(Consumer<BlockPos> consumer) {
        this.forEachPosition(blockPos -> {
            int count = 0;
            if (blockPos.getX() == this.minX() || blockPos.getX() == this.maxX())
                count++;
            if (blockPos.getY() == this.minY() || blockPos.getY() == this.maxY())
                count++;
            if (blockPos.getZ() == this.minZ() || blockPos.getZ() == this.maxZ())
                count++;
            if (count >= 2) {
                consumer.accept(blockPos);
            }
        });
    }

    public void slice(Direction.Axis axis, Consumer<IterableBoundingBox> consumer) {
        switch (axis) {
            case X -> {
                for (int y = this.minY(); y <= this.maxY(); y++)
                    for (int z = this.minZ(); z <= this.maxZ(); z++)
                        consumer.accept(new IterableBoundingBox(this.minX(), y, z, this.maxX(), y, z));
            }
            case Y -> {
                for (int x = this.minX(); x <= this.maxX(); x++)
                    for (int z = this.minZ(); z <= this.maxZ(); z++)
                        consumer.accept(new IterableBoundingBox(x, this.minY(), z, x, this.maxY(), z));
            }
            case Z -> {
                for (int x = this.minX(); x <= this.maxX(); x++)
                    for (int y = this.minY(); y <= this.maxY(); y++)
                        consumer.accept(new IterableBoundingBox(x, y, this.minZ(), x, y, this.maxZ()));
            }
        }
    }

    private int getX() {
        if (this.minX() != this.maxX())
            throw(new RuntimeException("non-noodle bounding box"));
        return this.minX();
    }

    private int getY() {
        if (this.minY() != this.maxY())
            throw(new RuntimeException("non-noodle bounding box"));
        return this.minY();
    }

    private int getZ() {
        if (this.minZ() != this.maxZ())
            throw(new RuntimeException("non-noodle bounding box"));
        return this.minZ();
    }

    public BlockPos getEndpoint(Direction direction) {
        return switch (direction) {
            case WEST  -> new BlockPos(this.minX(), this.getY(), this.getZ());
            case EAST  -> new BlockPos(this.maxX(), this.getY(), this.getZ());
            case DOWN  -> new BlockPos(this.getX(), this.minY(), this.getZ());
            case UP    -> new BlockPos(this.getX(), this.maxY(), this.getZ());
            case NORTH -> new BlockPos(this.getX(), this.getY(), this.minZ());
            case SOUTH -> new BlockPos(this.getX(), this.getY(), this.maxZ());
        };
    }

    // Backported from 1.18 because in 1.17 this function mutates state.
    public IterableBoundingBox expand(int offset) {
        return new IterableBoundingBox(this.minX() - offset, this.minY() - offset, this.minZ() - offset, this.maxX() + offset, this.maxY() + offset, this.maxZ() + offset);
    }
}
