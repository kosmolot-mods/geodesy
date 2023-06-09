package pl.kosma.geodesy;

import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.function.Consumer;

/**
 * A iterable block box.
 */
class IterableBlockBox extends BlockBox {

    /**
     * Creates a new iterable block box with specified minimum and maximum coordinates.
     * @param minX the minimum x coordinate
     * @param minY the minimum y coordinate
     * @param minZ the minimum z coordinate
     * @param maxX the maximum x coordinate
     * @param maxY the maximum y coordinate
     * @param maxZ the maximum z coordinate
     */
    public IterableBlockBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        super(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Creates a new iterable block box from a block box.
     * @param box the block box to create from
     */
    public IterableBlockBox(BlockBox box) {
        this(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
    }

    /**
     * Accepts the consumer at each block position in the box
     * @param consumer the consumer to accept
     */
    public void forEachPosition(Consumer<BlockPos> consumer) {
        for (int x = this.getMinX(); x <= this.getMaxX(); x++)
            for (int y = this.getMinY(); y <= this.getMaxY(); y++)
                for (int z = this.getMinZ(); z <= this.getMaxZ(); z++)
                    consumer.accept(new BlockPos(x, y, z));
    }

    /**
     * Accepts the consumer at each wall position in the box
     * @param consumer the consumer to accept
     */
    public void forEachWallPosition(Consumer<BlockPos> consumer) {
        this.forEachPosition(blockPos -> {
            if (blockPos.getX() == this.getMinX() || blockPos.getX() == this.getMaxX() ||
                    blockPos.getY() == this.getMinY() || blockPos.getY() == this.getMaxY() ||
                    blockPos.getZ() == this.getMinZ() || blockPos.getZ() == this.getMaxZ()) {
                consumer.accept(blockPos);
            }
        });
    }

    /**
     * Accepts the consumer at each edge position in the box
     * @param consumer the consumer to accept
     */
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

    /**
     * Accepts the consumer at each slice or rather, tube, of the box in the specified axis.
     * For example, this creates vertical tubes when the axis is {@link Direction.Axis#X}.
     * @param axis the axis to slice in
     * @param consumer the consumer to accept
     */
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

    /**
     * Get the x position of the box, if the x direction is one block wide.
     * Useful for getting the x position of a slice
     * @return the x position
     */
    private int getX() {
        if (this.getMinX() != this.getMaxX())
            throw(new RuntimeException("non-noodle bounding box"));
        return this.getMinX();
    }

    /**
     * Get the y position of the box, if the y direction is one block wide.
     * Useful for getting the y position of a slice
     * @return the y position
     */
    private int getY() {
        if (this.getMinY() != this.getMaxY())
            throw(new RuntimeException("non-noodle bounding box"));
        return this.getMinY();
    }

    /**
     * Get the z position of the box, if the z direction is one block wide.
     * Useful for getting the z position of a slice
     * @return the z position
     */
    private int getZ() {
        if (this.getMinZ() != this.getMaxZ())
            throw(new RuntimeException("non-noodle bounding box"));
        return this.getMinZ();
    }

    /**
     * Get the end points of the box, if the box is one a slice in that direciton.
     * @param direction the direction to get the end points in
     * @return the end point of the box (slice)
     */
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

    /**
     * Expands the box in each direction with the specific offset.
     * @param offset the offset to expand by
     * @return the expanded box
     * @implNote Back-ported from 1.18 because this method mutates state in 1.17.
     */
    public IterableBlockBox expand(int offset) {
        return new IterableBlockBox(this.getMinX() - offset, this.getMinY() - offset, this.getMinZ() - offset, this.getMaxX() + offset, this.getMaxY() + offset, this.getMaxZ() + offset);
    }
}
