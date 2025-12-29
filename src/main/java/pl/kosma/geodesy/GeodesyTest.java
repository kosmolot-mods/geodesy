package pl.kosma.geodesy;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

public class GeodesyTest {
    @GameTest(structure = "geodesy:geodesytest.test", maxTicks = 500)
    public void test(TestContext context) {
        MinecraftServer server = context.getWorld().getServer();
        CommandManager commandManager = server.getCommandManager();
        ServerCommandSource commandSource = server.getCommandSource();

        BlockPos absolutePos = context.getAbsolutePos(new BlockPos(18, 18, 18));
        commandManager.parseAndExecute(commandSource, "/geodesy area " + absolutePos.getX() + " " + absolutePos.getY() + " " + absolutePos.getZ() + " " + absolutePos.getX() + " " + absolutePos.getY() + " " + absolutePos.getZ());
        commandManager.parseAndExecute(commandSource, "/geodesy analyze");
        commandManager.parseAndExecute(commandSource, "/geodesy project north east down");

        new IterableBlockBox(17, 17, 14, 19, 19, 14).forEachEdgePosition(pos -> context.setBlockState(pos, Blocks.SLIME_BLOCK));
        context.setBlockState(19, 17, 13, Blocks.ZOMBIE_WALL_HEAD);
        context.setBlockState(19, 18, 13, Blocks.ZOMBIE_WALL_HEAD);
        context.setBlockState(19, 19, 13, Blocks.ZOMBIE_WALL_HEAD);
        context.setBlockState(18, 17, 13, Blocks.WITHER_SKELETON_WALL_SKULL);

        new IterableBlockBox(22, 17, 17, 22, 19, 19).forEachEdgePosition(pos -> context.setBlockState(pos, Blocks.SLIME_BLOCK));
        context.setBlockState(23, 17, 19, Blocks.ZOMBIE_WALL_HEAD);
        context.setBlockState(23, 18, 19, Blocks.ZOMBIE_WALL_HEAD);
        context.setBlockState(23, 19, 19, Blocks.ZOMBIE_WALL_HEAD);
        context.setBlockState(23, 17, 18, Blocks.WITHER_SKELETON_WALL_SKULL);

        commandManager.parseAndExecute(commandSource, "/geodesy assemble");
        context.putAndRemoveRedstoneBlock(new BlockPos(19, 17, 2), 1);
        context.waitAndRun(150, () -> context.putAndRemoveRedstoneBlock(new BlockPos(34, 17, 19), 1));
        context.waitAndRun(300, () -> {
            new IterableBlockBox(17, 17, 15, 19, 19, 15).forEachEdgePosition(pos -> context.expectBlock(Blocks.SLIME_BLOCK, pos));
            new IterableBlockBox(21, 17, 17, 21, 19, 19).forEachEdgePosition(pos -> context.expectBlock(Blocks.SLIME_BLOCK, pos));
            context.expectBlock(Blocks.REDSTONE_LAMP, 19, 17, 12);
            context.expectBlock(Blocks.REDSTONE_LAMP, 24, 17, 19);
            context.expectBlock(Blocks.BUDDING_AMETHYST, 18, 18, 18);
            context.complete();
        });
    }
}
