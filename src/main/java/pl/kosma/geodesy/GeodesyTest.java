package pl.kosma.geodesy;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;

public class GeodesyTest {
    @GameTest(structure = "geodesy:geodesytest.test", maxTicks = 500)
    public void test(GameTestHelper context) {
        MinecraftServer server = context.getLevel().getServer();
        Commands commandManager = server.getCommands();
        CommandSourceStack commandSource = server.createCommandSourceStack();

        commandManager.performPrefixedCommand(commandSource, "/gamerule random_tick_speed 0");

        BlockPos absolutePos = context.absolutePos(new BlockPos(18, 18, 18));
        commandManager.performPrefixedCommand(commandSource, "/geodesy area " + absolutePos.getX() + " " + absolutePos.getY() + " " + absolutePos.getZ() + " " + absolutePos.getX() + " " + absolutePos.getY() + " " + absolutePos.getZ());
        commandManager.performPrefixedCommand(commandSource, "/geodesy analyze");
        commandManager.performPrefixedCommand(commandSource, "/geodesy project north east down");

        commandManager.performPrefixedCommand(commandSource, "/geodesy solve");
        context.runAfterDelay(100, () -> {
            context.assertBlockPresent(Blocks.ZOMBIE_WALL_HEAD, 17, 17, 13);
            context.assertBlockPresent(Blocks.ZOMBIE_WALL_HEAD, 17, 18, 13);
            context.assertBlockPresent(Blocks.ZOMBIE_WALL_HEAD, 17, 19, 13);
            context.assertBlockPresent(Blocks.WITHER_SKELETON_WALL_SKULL, 18, 17, 13);

            context.assertBlockPresent(Blocks.ZOMBIE_WALL_HEAD, 23, 17, 17);
            context.assertBlockPresent(Blocks.ZOMBIE_WALL_HEAD, 23, 17, 18);
            context.assertBlockPresent(Blocks.ZOMBIE_WALL_HEAD, 23, 17, 19);
            context.assertBlockPresent(Blocks.WITHER_SKELETON_WALL_SKULL, 23, 18, 17);

            commandManager.performPrefixedCommand(commandSource, "/geodesy assemble");
            context.pulseRedstone(new BlockPos(17, 17, 2), 1);
        });

        context.runAfterDelay(250, () -> context.pulseRedstone(new BlockPos(34, 16, 18), 1));
        context.runAfterDelay(400, () -> {
            context.assertBlockPresent(Blocks.SLIME_BLOCK, 18, 17, 15);
            context.assertBlockPresent(Blocks.SLIME_BLOCK, 21, 18, 17);
            context.assertBlockPresent(Blocks.REDSTONE_LAMP, 17, 17, 12);
            context.assertBlockPresent(Blocks.REDSTONE_LAMP, 24, 17, 17);

            context.assertBlockPresent(Blocks.BUDDING_AMETHYST, 18, 18, 18);
            context.assertBlockPresent(Blocks.AIR, 17, 18, 18);
            context.assertBlockPresent(Blocks.AIR, 19, 18, 18);
            context.assertBlockPresent(Blocks.AIR, 18, 17, 18);
            context.assertBlockPresent(Blocks.AIR, 18, 19, 18);
            context.assertBlockPresent(Blocks.AIR, 18, 18, 17);
            context.assertBlockPresent(Blocks.AIR, 18, 18, 19);

            context.succeed();
        });
    }
}
