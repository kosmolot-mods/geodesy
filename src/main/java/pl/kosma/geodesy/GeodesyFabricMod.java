package pl.kosma.geodesy;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class GeodesyFabricMod implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("GeodesyFabricMod");

    static private final Map<UUID, GeodesyCore> perPlayerCore = new HashMap<>();

    private GeodesyCore getPerPlayerCore(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        if (!perPlayerCore.containsKey(uuid)) {
            perPlayerCore.put(uuid, new GeodesyCore());
        }
        GeodesyCore core = perPlayerCore.get(uuid);
        core.setPlayerEntity(player);
        return core;
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("geodesy")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(literal("area")
                        .then(argument("start", BlockPosArgumentType.blockPos())
                            .then(argument("end", BlockPosArgumentType.blockPos())
                                .executes(context -> {
                                    try {
                                        GeodesyCore core = getPerPlayerCore(context.getSource().getPlayer());
                                        World world = context.getSource().getPlayer().getWorld();
                                        BlockPos startPos = BlockPosArgumentType.getBlockPos(context, "start");
                                        BlockPos endPos = BlockPosArgumentType.getBlockPos(context, "end");
                                        core.geodesyArea(world, startPos, endPos);
                                        return SINGLE_SUCCESS;
                                    }
                                    catch (Exception e) {
                                        LOGGER.error("area", e);
                                        throw (e);
                                    }
                                }))))
                    .then(literal("analyze").executes(context -> {
                        try {
                            GeodesyCore core = getPerPlayerCore(context.getSource().getPlayer());
                            core.geodesyAnalyze();
                            return SINGLE_SUCCESS;
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
                                    GeodesyCore core = getPerPlayerCore(context.getSource().getPlayer());
                                    String directionsString = StringArgumentType.getString(context, "directions").trim().toUpperCase();
                                    String[] directionsArray = directionsString.split("\\s+");
                                    Direction[] directions = Arrays.stream(directionsArray).map(Direction::valueOf).toArray(Direction[]::new);
                                    core.geodesyProject(directions);
                                    return SINGLE_SUCCESS;
                                }
                                catch (Exception e) {
                                    LOGGER.error("project", e);
                                    throw (e);
                                }
                            })))
                    .then(literal("assemble").executes(context -> {
                        try {
                            GeodesyCore core = getPerPlayerCore(context.getSource().getPlayer());
                            core.geodesyAssemble();
                            return SINGLE_SUCCESS;
                        }
                        catch (Exception e) {
                            LOGGER.error("assemble", e);
                            throw (e);
                        }
                    }))
                    .executes(context -> {
                        try {
                            GeodesyCore core = getPerPlayerCore(context.getSource().getPlayer());
                            core.geodesyGeodesy();
                            return SINGLE_SUCCESS;
                        }
                        catch (Exception e) {
                            LOGGER.error("geodesy", e);
                            throw (e);
                        }
                    })
            );
        });
    }
}
