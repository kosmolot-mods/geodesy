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

        commandManager.parseAndExecute(commandSource, "/gamerule random_tick_speed 0");

        BlockPos absolutePos = context.getAbsolutePos(new BlockPos(18, 18, 18));
        commandManager.parseAndExecute(commandSource, "/geodesy area " + absolutePos.getX() + " " + absolutePos.getY() + " " + absolutePos.getZ() + " " + absolutePos.getX() + " " + absolutePos.getY() + " " + absolutePos.getZ());
        commandManager.parseAndExecute(commandSource, "/geodesy analyze");
        commandManager.parseAndExecute(commandSource, "/geodesy project north east down");

        commandManager.parseAndExecute(commandSource, "/geodesy solve");
        context.waitAndRun(100, () -> {
            context.expectBlock(Blocks.ZOMBIE_WALL_HEAD, 17, 17, 13);
            context.expectBlock(Blocks.ZOMBIE_WALL_HEAD, 17, 18, 13);
            context.expectBlock(Blocks.ZOMBIE_WALL_HEAD, 17, 19, 13);
            context.expectBlock(Blocks.WITHER_SKELETON_WALL_SKULL, 18, 17, 13);

            context.expectBlock(Blocks.ZOMBIE_WALL_HEAD, 23, 17, 17);
            context.expectBlock(Blocks.ZOMBIE_WALL_HEAD, 23, 17, 18);
            context.expectBlock(Blocks.ZOMBIE_WALL_HEAD, 23, 17, 19);
            context.expectBlock(Blocks.WITHER_SKELETON_WALL_SKULL, 23, 18, 17);

            commandManager.parseAndExecute(commandSource, "/geodesy assemble");
            context.putAndRemoveRedstoneBlock(new BlockPos(17, 17, 2), 1);
        });

        context.waitAndRun(250, () -> context.putAndRemoveRedstoneBlock(new BlockPos(34, 16, 18), 1));
        context.waitAndRun(400, () -> {
            context.expectBlock(Blocks.SLIME_BLOCK, 18, 17, 15);
            context.expectBlock(Blocks.SLIME_BLOCK, 21, 18, 17);
            context.expectBlock(Blocks.REDSTONE_LAMP, 17, 17, 12);
            context.expectBlock(Blocks.REDSTONE_LAMP, 24, 17, 17);

            context.expectBlock(Blocks.BUDDING_AMETHYST, 18, 18, 18);
            context.expectBlock(Blocks.AIR, 17, 18, 18);
            context.expectBlock(Blocks.AIR, 19, 18, 18);
            context.expectBlock(Blocks.AIR, 18, 17, 18);
            context.expectBlock(Blocks.AIR, 18, 19, 18);
            context.expectBlock(Blocks.AIR, 18, 18, 17);
            context.expectBlock(Blocks.AIR, 18, 18, 19);

            context.complete();
        });
    }
}
