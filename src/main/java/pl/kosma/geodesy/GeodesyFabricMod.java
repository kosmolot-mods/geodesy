package pl.kosma.geodesy;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.serialize.ConstantArgumentSerializer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

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
        ArgumentTypeRegistry.registerArgumentType(new Identifier("geodesy", "direction"), DirectionArgumentType.class, ConstantArgumentSerializer.of(DirectionArgumentType::direction));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("geodesy")
                    .requires(source -> source.hasPermissionLevel(2))
                    // debug only - command line custom water collection system generator
/*
                    .then(literal("water")
                        .then(argument("start", BlockPosArgumentType.blockPos())
                            .then(argument("sizeX", IntegerArgumentType.integer(9, 18))
                                    .then(argument("sizeZ", IntegerArgumentType.integer(9, 18))
                                            .then(argument("waterX", IntegerArgumentType.integer(0, 9))
                                                    .then(argument("waterZ", IntegerArgumentType.integer(0, 9))
                                                        .executes(context -> {
                                                            try {
                                                                World world = context.getSource().getPlayer().getWorld();
                                                                BlockPos startPos = BlockPosArgumentType.getBlockPos(context, "start");
                                                                int sizeX = IntegerArgumentType.getInteger(context, "sizeX");
                                                                int sizeZ = IntegerArgumentType.getInteger(context, "sizeZ");
                                                                int waterX = IntegerArgumentType.getInteger(context, "waterX");
                                                                int waterZ = IntegerArgumentType.getInteger(context, "waterZ");
                                                                context.getSource().getServer().execute(() -> WaterCollectionSystemGenerator.generateWater(world, startPos, sizeX, sizeZ, waterX, waterZ));
                                                                return SINGLE_SUCCESS;
                                                            }
                                                            catch (Exception e) {
                                                                LOGGER.error("area", e);
                                                                throw (e);
                                                            }
                                                        })))))))


*/
/*
                    // debug only - directly execute the water collection generator
                    .then(literal("watergen")
                            .then(argument("start", BlockPosArgumentType.blockPos())
                                    .then(argument("sizeX", IntegerArgumentType.integer())
                                            .then(argument("sizeZ", IntegerArgumentType.integer())
                                            .executes(context -> {
                                                    try {
                                                        World world = context.getSource().getPlayer().getWorld();
                                                        BlockPos startPos = BlockPosArgumentType.getBlockPos(context, "start");
                                                        int sizeX = IntegerArgumentType.getInteger(context, "sizeX");
                                                        int sizeZ = IntegerArgumentType.getInteger(context, "sizeZ");
                                                        BlockBox area = new BlockBox(startPos.getX(), startPos.getY(), startPos.getZ(),
                                                                startPos.getX() + sizeX - 1, startPos.getY(), startPos.getZ() + sizeZ - 1);
                                                        context.getSource().getServer().execute(() -> {
                                                            new IterableBlockBox(area).forEachPosition(blockPos -> {
                                                                world.setBlockState(blockPos, Blocks.AIR.getDefaultState());
                                                            });
                                                            WaterCollectionSystemGenerator.generate(world, area);
                                                        });
                                                        return SINGLE_SUCCESS;
                                                    }
                                                    catch (Exception e) {
                                                        LOGGER.error("area", e);
                                                        throw (e);
                                                    }
                                            })))))
*/
                    .then(literal("area")
                            .then(argument("start", BlockPosArgumentType.blockPos())
                                    .then(argument("end", BlockPosArgumentType.blockPos())
                                            .executes(context -> {
                                                try {
                                                    GeodesyCore core = getPerPlayerCore(context.getSource().getPlayer());
                                                    ServerWorld world = context.getSource().getPlayer().getServerWorld();
                                                    BlockPos startPos = BlockPosArgumentType.getValidBlockPos(context, "start");
                                                    BlockPos endPos = BlockPosArgumentType.getValidBlockPos(context, "end");
                                                    context.getSource().getServer().execute(() -> core.geodesyArea(world, startPos, endPos));
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
                            context.getSource().getServer().execute(() -> core.geodesyAnalyze());
                            return SINGLE_SUCCESS;
                        }
                        catch (Exception e) {
                            LOGGER.error("analyze", e);
                            throw (e);
                        }
                    }))
                    .then(literal("project")
                        .then(argument("direction1", DirectionArgumentType.direction())
                            .then(argument("direction2", DirectionArgumentType.direction())
                                .then(argument("direction3", DirectionArgumentType.direction())
                                    .then(argument("direction4", DirectionArgumentType.direction())
                                        .then(argument("direction5", DirectionArgumentType.direction())
                                            .then(argument("direction6", DirectionArgumentType.direction())
                                                .executes(context -> geodesyProjectCommand(context,6)))
                                            .executes(context -> geodesyProjectCommand(context,5)))
                                        .executes(context -> geodesyProjectCommand(context,4)))
                                    .executes(context -> geodesyProjectCommand(context,3)))
                                .executes(context -> geodesyProjectCommand(context,2)))
                            .executes(context -> geodesyProjectCommand(context,1)))
                        .executes(context -> geodesyProjectCommand(context,0)))
                    .then(literal("assemble").executes(context -> {
                        try {
                            GeodesyCore core = getPerPlayerCore(context.getSource().getPlayer());
                            context.getSource().getServer().execute(() -> core.geodesyAssemble());
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
                            context.getSource().getServer().execute(() -> core.geodesyGeodesy());
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

    private int geodesyProjectCommand(CommandContext<ServerCommandSource> context, int argumentIndex) {
        try {
            GeodesyCore core = getPerPlayerCore(context.getSource().getPlayer());
            Set<Direction> directions = new LinkedHashSet<>(argumentIndex);
            for (int i = 1; i <= argumentIndex; i++) {
                directions.add(DirectionArgumentType.getDirection(context, "direction" + i));
            }
            context.getSource().getServer().execute(() -> core.geodesyProject(directions.isEmpty() ? null : directions.toArray(new Direction[argumentIndex])));
            return SINGLE_SUCCESS;
        } catch (Exception e) {
            LOGGER.error("project", e);
            throw (e);
        }
    }
}
