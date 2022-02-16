package pl.kosma.geodesy;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class GeodesyFabricMod implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("GeodesyFabricMod");

    private GeodesyCore core;

    @Override
    public void onInitialize() {
        core = new GeodesyCore();
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            dispatcher.register(literal("geodesy")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(literal("area")
                        .then(argument("start", BlockPosArgumentType.blockPos())
                            .then(argument("end", BlockPosArgumentType.blockPos())
                                .executes(context -> {
                                    try {
                                        World world = context.getSource().getPlayer().getWorld();
                                        BlockPos startPos = BlockPosArgumentType.getBlockPos(context, "start");
                                        BlockPos endPos = BlockPosArgumentType.getBlockPos(context, "end");
                                        core.geodesyArea(world, startPos, endPos);
                                        return Command.SINGLE_SUCCESS;
                                    }
                                    catch (Exception e) {
                                        LOGGER.error("area", e);
                                        throw (e);
                                    }
                                }))))
                    .then(literal("analyze").executes(context -> {
                        try {
                            core.geodesyAnalyze();
                            return Command.SINGLE_SUCCESS;
                        }
                        catch (Exception e) {
                            LOGGER.error("analyze", e);
                            throw (e);
                        }
                    }))
                    .then(literal("project")
                        .then(argument("directions", StringArgumentType.greedyString())
                            .executes(context -> {
                                try {
                                    String directionsString = StringArgumentType.getString(context, "directions").trim().toUpperCase();
                                    String[] directionsArray = directionsString.split("\\s+");
                                    Direction[] directions = Arrays.stream(directionsArray).map(Direction::valueOf).toArray(Direction[]::new);
                                    core.geodesyProject(directions);
                                    return Command.SINGLE_SUCCESS;
                                }
                                catch (Exception e) {
                                    LOGGER.error("project", e);
                                    throw (e);
                                }
                            })))
                    .then(literal("assemble").executes(context -> {
                        try {
                            core.geodesyAssemble();
                            return Command.SINGLE_SUCCESS;
                        }
                        catch (Exception e) {
                            LOGGER.error("assemble", e);
                            throw (e);
                        }
                    }))
            );
        });
    }

}
