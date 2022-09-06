package pl.kosma.geodesy;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

public class WaterCollectionSystemGenerator {

    static final Logger LOGGER = LoggerFactory.getLogger(WaterCollectionSystemGenerator.class.getName());

    /*
     * Debug-only water stream generator - development only, not for player use.
     */
    public static void generateWater(World world, BlockPos start, int sizeX, int sizeZ, int waterX, int waterZ) {
        BlockState water = Blocks.WATER.getDefaultState();
        BlockPos end = start.offset(Direction.Axis.X, sizeX-1).offset(Direction.Axis.Z, sizeZ-1);

        // Erase the area
        IterableBlockBox area = new IterableBlockBox(BlockBox.encompassPositions(Set.of(start, end, start.up())).orElseThrow());
        area.forEachPosition(blockPos -> {
            world.setBlockState(blockPos, Blocks.AIR.getDefaultState());
        });

        int waterOffset = ((sizeX > 10) && (sizeZ > 10)) ? 1 : 1;

        // Place floating water sources in the four corner
        world.setBlockState(start.offset(Direction.UP, waterOffset), water);
        world.setBlockState(start.offset(Direction.UP, waterOffset).offset(Direction.Axis.X, sizeX-1), water);
        world.setBlockState(start.offset(Direction.UP, waterOffset).offset(Direction.Axis.X, sizeX-1).offset(Direction.Axis.Z, sizeZ-1), water);
        world.setBlockState(start.offset(Direction.UP, waterOffset).offset(Direction.Axis.Z, sizeZ-1), water);

        // Place extra water blocks along the edges. Skip the first/last 2 blocks
        // because placing them would flood the entire area with water sources.
        for (int x=0; x<waterX; x++) {
            world.setBlockState(new BlockPos(start.getX() + 2 + x, start.getY(), start.getZ()), water);
            world.setBlockState(new BlockPos(start.getX() + 2 + x, start.getY(), end.getZ()), water);
            world.setBlockState(new BlockPos(end.getX() - 2 - x, start.getY(), start.getZ()), water);
            world.setBlockState(new BlockPos(end.getX() - 2 - x, start.getY(), end.getZ()), water);
        }
        for (int z=0; z<waterZ; z++) {
            world.setBlockState(new BlockPos(start.getX(), start.getY(), start.getZ() + 2 + z), water);
            world.setBlockState(new BlockPos(start.getX(), start.getY(), end.getZ() - 2 - z), water);
            world.setBlockState(new BlockPos(end.getX(), start.getY(), start.getZ() + 2 + z), water);
            world.setBlockState(new BlockPos(end.getX(), start.getY(), end.getZ() - 2 - z), water);
        }
    }

    /*
     * Damn watergen, man. Works from 7x7 up to... nominally 18x18, but can generate bigger without failing.
     */
    public static void generate(World world, BlockBox area) {
        BlockState water = Blocks.WATER.getDefaultState();

        int sizeX = area.getBlockCountX();
        int sizeZ = area.getBlockCountZ();

        // Calculate drain area - it's always 1x1 to 2x2, centered.
        int drainSizeX = (sizeX % 2) == 0 ? 2 : 1;
        int drainSizeZ = (sizeZ % 2) == 0 ? 2 : 1;
        // Calculate the fill coefficient. It was derived by drinking lots of coffee.
        int fill = (sizeX + sizeZ - drainSizeX - drainSizeZ)/2 - 8;

        // Special case: for side lengths larger than 18 blocks we have no choice but to enlarge the drain.
        // We do this *after* we calculate the fill coefficient. Why? Because it works better, idk.
        if (sizeX > 18)
            drainSizeX = sizeX-16;
        if (sizeZ > 18)
            drainSizeZ = sizeZ-16;

        // Calculate drain position.
        int drainStartX = area.getMinX() + sizeX/2 - drainSizeX/2;
        int drainStartZ = area.getMinZ() + sizeZ/2 - drainSizeZ/2;
        int drainY = area.getMinY() - 1;

        // Fill the drain with signs (immovable no-hitbox blocks).
        IterableBlockBox drain = new IterableBlockBox(
                drainStartX, drainY, drainStartZ,
                drainStartX + drainSizeX - 1, drainY, drainStartZ + drainSizeZ - 1);
        drain.forEachPosition(blockPos -> {
            world.setBlockState(blockPos, Blocks.SPRUCE_WALL_SIGN.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.SOUTH));
        });

        LOGGER.info("Size: {} x {}", sizeX, sizeZ);
        LOGGER.info("Drain: {} x {}", drainSizeX, drainSizeZ);
        LOGGER.info("Fill: {}", fill);

        /*
         * Water layout example for fill == 3:
         *
         * ####
         * #
         * #
         *
         * That is: three waters counter-clockwise, but four clockwise.
         * I have derived this algorithm in a feverish dream, using pen, paper and blocks.
         * It works correctly from 9x9 to 18x18; larger sizes require some manual fixing
         * which shouldn't be too bad. Whatever. I am lazy and tired.
         */

        // We start generation in the corners, proceeding clockwise.
        // Write it as data because I like doing it like this.
        record Corner(BlockPos start, Direction direction) {};
        Corner generationCorners[] = {
                new Corner(new BlockPos(area.getMinX(), area.getMinY(), area.getMinZ()), Direction.EAST),
                new Corner(new BlockPos(area.getMaxX(), area.getMinY(), area.getMinZ()), Direction.SOUTH),
                new Corner(new BlockPos(area.getMaxX(), area.getMinY(), area.getMaxZ()), Direction.WEST),
                new Corner(new BlockPos(area.getMinX(), area.getMinY(), area.getMaxZ()), Direction.NORTH),
        };

        if (fill < 1) {
            // Zero (or lower) fill level is just a single water source in each corner.
            for (Corner corner: generationCorners) {
                world.setBlockState(corner.start, water);
            }
        } else {
            // One (or higher) fill level uses the elevated water source trick in order
            // to prevent more water sources forming where we don't want them.
            for (Corner corner : generationCorners) {
                // Place a water source one block above the ground.
                world.setBlockState(corner.start.up(), water);
                // Place the clockwise water sources, starting at block 2 (see trick above).
                for (int i = 2; i <= fill; i++)
                    world.setBlockState(corner.start.offset(corner.direction, i), water);
                // Place the counterclockwise water sources, starting at block 2 (see trick above).
                for (int i = 2; i < fill; i++)
                    world.setBlockState(corner.start.offset(corner.direction.rotateYClockwise(), i), water);
                // Special case found in testing: in systems at least 18 long, overfill the counterclockwise arm too.
                world.setBlockState(corner.start.offset(corner.direction.rotateYClockwise(), fill), water);
            }
        }
    }

}
