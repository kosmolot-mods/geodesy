package pl.kosma.geodesy.projection;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;

import com.microsoft.z3.*;
import com.microsoft.z3.enumerations.Z3_lbool;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.kosma.geodesy.GeodesyCore;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Projection {
    private static final Logger LOGGER = LoggerFactory.getLogger(Projection.class.getCanonicalName());
    private GeodesyCore core;
    private Set<GeodesyBlockPos> buddingAmethysts;
    private Set<GeodesyBlockPos> amethystClusters;
    private Set<GeodesyPlanePos> slices;

    private Map<GeodesyBlockPos, BoolExpr> budBoolMap;
    private Map<GeodesyBlockPos, BoolExpr> clusterBoolMap;
    private Map<GeodesyPlanePos, BoolExpr> planePosBoolMap;

    private Map<GeodesyBlockPos, Set<GeodesyPlanePos>> blockPosPlanePosMap;
    private Map<GeodesyPlanePos, Set<GeodesyBlockPos>> planePosBlockPosMap;

    private Context ctx;
    private Solver solver;

    private Model model;

    private IntExpr nrOfHarvestedClusters;
    private IntExpr nrOfProjections;

    public Projection () {
        initTestGeode();
        initZ3();
    }
    public Projection (GeodesyCore core, List<BlockPos> buddingAmethystPositions) {
        this.core = core;
        initGeode(buddingAmethystPositions);
        initZ3();
    }

    private void initTestGeode() {
        this.buddingAmethysts = ExampleGeode.buddingAmethysts;
        initGeode(buddingAmethysts.stream().map(GeodesyBlockPos::toBlockPos).toList());
    }
    private void initGeode(List<BlockPos> buddingAmethystPositions) {
        this.buddingAmethysts = buddingAmethystPositions.stream()
            .map(GeodesyBlockPos::fromBlockPos)
            .collect(Collectors.toSet());

        // Generate potential cluster locations from the buds
        // NOTE: We intentionally leave in positions that overlap with budding amethysts.
        this.amethystClusters = buddingAmethysts.stream()
            .flatMap(GeodesyBlockPos::neighbours)
            .collect(Collectors.toSet());

        // For all buds, clusters, and planes, generate the slices and a mapping from blockPos to planePos
        this.slices = new HashSet<>();
        this.blockPosPlanePosMap = new HashMap<>();
        this.planePosBlockPosMap = new HashMap<>();
        for (Direction.Axis plane : Direction.Axis.values()) {
            for (GeodesyBlockPos blockPos : Stream.of(buddingAmethysts, amethystClusters).flatMap(Set::stream).toList()) {
                GeodesyPlanePos planePos = GeodesyPlanePos.fromBlockPos(blockPos, plane);
                slices.add(planePos);
                blockPosPlanePosMap.computeIfAbsent(blockPos, k -> new HashSet<>()).add(planePos);
                planePosBlockPosMap.computeIfAbsent(planePos, k -> new HashSet<>()).add(blockPos);
            }
        }
    }

    private void initZ3() {
        this.ctx = new Context();
        this.solver = ctx.mkSolver();
        this.model = null;
        // For buds, clusters, and slices, create a map from the object to the z3 bool expression.
        this.budBoolMap = this.buddingAmethysts.stream().collect(
            Collectors.toMap(
                Function.identity(),
                bud -> ctx.mkBoolConst("budding_amethyst__" + bud.x() + "__" + bud.y() + "__" + bud.z())));
        this.clusterBoolMap = this.amethystClusters.stream().collect(
            Collectors.toMap(
                Function.identity(),
                cluster -> ctx.mkBoolConst(
                    "amethyst_cluster__" + cluster.x() + "__" + cluster.y() + "__" + cluster.z())));
        this.planePosBoolMap = this.slices.stream().collect(
            Collectors.toMap(
                Function.identity(),
                slice -> ctx.mkBoolConst("slice__" + slice.plane() + "__" + slice.a() + "__" + slice.b())));
    }

    private void buildBudClusterRelations() {
        //########################################################################################################
        //# Set relations between amethyst clusters and budding amethysts                                        #
        //# We have three relations to define:                                                                   #
        //# Relation 1: Amethyst Cluster is true -> one of the neighbouring Budding Amethysts is true            #
        //# Relation 2: Budding Amethyst is true                                                                 #
        //#   -> all neighbours either (have no bud possibility and have a crystal) or (have a bud or a crystal) #
        //# Relation 3: Budding Amethyst xor Amethyst Cluster                                                    #
        //########################################################################################################

        // Relation 1: Amethyst Cluster is true -> one of the neighbouring Budding Amethysts is true
        Stream<BoolExpr> clusterImpliesNeighbourBudC = this.amethystClusters.stream()
            .map(cluster -> ctx.mkImplies(
                this.clusterBoolMap.get(cluster),
                ctx.mkOr(cluster.neighbours()
                    .filter(budBoolMap::containsKey)
                    .map(this.budBoolMap::get)
                    .toArray(BoolExpr[]::new))));

        // Relation 2: Budding Amethyst is true
        //   -> all neighbours either (have no bud possibility and have a crystal) or (have a bud or a crystal)
        Stream<BoolExpr> budImpliesPossibleNeighbourClusterC = this.buddingAmethysts.stream()
            .map(bud -> ctx.mkImplies(
                this.budBoolMap.get(bud),
                ctx.mkAnd(bud.neighbours()
                    .map(neighbour ->
                        buddingAmethysts.contains(neighbour)
                        ? ctx.mkOr(
                            budBoolMap.get(neighbour),
                            clusterBoolMap.get(neighbour))
                        : clusterBoolMap.get(neighbour))
                    .toArray(BoolExpr[]::new))));

        // Relation 3: Budding Amethyst xor Amethyst Cluster
        Stream<BoolExpr> budXorClusterC = amethystClusters.stream()
            .filter(buddingAmethysts::contains) // Intersect both sets
            .map(blockPos -> ctx.mkXor(clusterBoolMap.get(blockPos), budBoolMap.get(blockPos)));

        this.solver.add(clusterImpliesNeighbourBudC.toArray(BoolExpr[]::new));
        this.solver.add(budImpliesPossibleNeighbourClusterC.toArray(BoolExpr[]::new));
        this.solver.add(budXorClusterC.toArray(BoolExpr[]::new));
    }

    private void buildProjectionRelations() {
        //###############################################################################################
        //# Set projection relations
        //# We have three relations to define:
        //# Relation 1: For all buds a slice would clear, the slice is active xor the bud is active
        //# Relation 2: 1x1 holes in the vertical (y) plane cannot exist
        //# Relation 3: 1x1 holes in the horizontal (x, z) planes can exist in specific scenarios
        //###############################################################################################

        // Set relation 1: For all buds a slice would clear, the slice is active xor the bud is active
        // NOTE: Technically, this condition limits the completeness of the problem.
        //       If a slice covers two buds, and removing only one of those buds could lead to improved
        //       cluster coverage (through one of the other two slices), then that scenario cannot be detected.
        //       The condition xor(slice, or(all buds that the slice clears)) would be the constraint that
        //       could replace the current constraint with perfect soundness and completeness, but in practice,
        //       it performs much worse.
        //       With the complete constraint, getting to ~345 harvested clusters can already take minutes,
        //       whereas with the incomplete constraint, getting to 360 (with 361 being unsat) takes 5 seconds.
        //       While that leaves no guarantee that 360 is truly the limit, it's much more practical for
        //       the purposes of quickly getting a (very) optimal projection.
        Stream<BoolExpr> budXorSliceC = blockPosPlanePosMap.entrySet().stream()
            .filter(e -> buddingAmethysts.contains(e.getKey()))
            .flatMap(e -> e.getValue().stream()
                .map(planePos -> ctx.mkXor(planePosBoolMap.get(planePos), budBoolMap.get(e.getKey()))));

        // For relations 2 and 3, we need to identify potential 1x1 holes first:
        Set<GeodesyPlanePos> potentialOneByOneHoles = slices.stream()
                .filter(slice -> slice.neighbours().allMatch(slices::contains))
                .collect(Collectors.toSet());

        // Set relation 2: 1x1 holes in the vertical (y) plane cannot exist:
        // Written as:
        // If a potential hole in the y plane is active,
        // then at least one of its neighbours must be active too, so it is not a 1x1 hole.
        Stream<BoolExpr> blockVerticalOneByOneHolesC = potentialOneByOneHoles.stream()
                .filter(planePos -> planePos.plane() == Direction.Axis.Y)
                .map(planePos -> ctx.mkImplies(
                        planePosBoolMap.get(planePos),
                        ctx.mkOr(planePos.neighbours()
                                .map(planePosBoolMap::get)
                                .toArray(BoolExpr[]::new))));

        // For relation 3, we must first create a map from each potential hole to a list of up to three sets of
        // projections in a specific shape.
        // If slices can be placed for all positions in at least one of those sets, the potential hole could be
        // harvested even if it is a 1x1 hole.
        // The following holes allow for the projection to be active
        //     B
        //     B
        //   AA#CC
        //    #H#
        //     #
        // Where # is blocked, H is the hole, and all A's, B's, or C's have to be free
        Map<GeodesyPlanePos, List<Set<BoolExpr>>> potentialHolesToRequiredProjectionSets = new HashMap<>();
        for (GeodesyPlanePos planePos : potentialOneByOneHoles) {
            if (planePos.plane() == Direction.Axis.Y) {
                continue;
            }
            potentialHolesToRequiredProjectionSets.put(planePos,
                Stream.of(Set.of(List.of(-2, 1), List.of(-1, 1)),
                          Set.of(List.of(0, 2), List.of(0, 3)),
                          Set.of(List.of(1, 1), List.of(1, 2)))
                .map(offsetSet -> offsetSet.stream()
                    .map(offset -> planePos.offset(offset.get(0), offset.get(1)))
                    .filter(slices::contains)
                    .map(planePosBoolMap::get)
                    .collect(Collectors.toSet())
                ).toList());
        }

        // Set relation 3: 1x1 holes in the horizontal (x, z) planes can exist in specific scenarios
        // Written as:
        // A potential hole being active while its neighbours are inactive, which is therefore a 1x1 hole,
        // requires at least one of the sets to be fully
        // active so the original hole can be powered.
        // NOTE: It is intended for sets to sometimes be empty. It will just lead to an empty `and()`,
        //       which is equivalent to `true` and therefore does not pose a problem.
        Stream<BoolExpr> blockSpecificHorizontalOneByOneHolesC = potentialOneByOneHoles.stream()
            .filter(planePos -> planePos.plane() != Direction.Axis.Y)
            .map(planePos -> ctx.mkImplies(
                ctx.mkAnd(planePosBoolMap.get(planePos),
                    ctx.mkAnd(planePos.neighbours()
                        .map(planePosBoolMap::get)
                        .map(ctx::mkNot).toArray(BoolExpr[]::new))),
                ctx.mkOr(potentialHolesToRequiredProjectionSets.get(planePos).stream()
                    .map(required_active_group -> ctx.mkAnd(required_active_group.toArray(BoolExpr[]::new)))
                    .toArray(BoolExpr[]::new))
            ));

        this.solver.add(budXorSliceC.toArray(BoolExpr[]::new));
        this.solver.add(blockVerticalOneByOneHolesC.toArray(BoolExpr[]::new));
        this.solver.add(blockSpecificHorizontalOneByOneHolesC.toArray(BoolExpr[]::new));
    }

    private void buildMetrics() {
        // TODO: Figure out how to make the below expressions sound in terms of typing
        // It works in practice, but in terms of typing it dies.
        nrOfHarvestedClusters = ctx.mkIntConst("nr_of_harvested_clusters");

        // Take the sum of:
        // for each cluster, if it is active, and at least one of the slices that harvests it
        // is active, count the cluster as 1.
        ArithExpr<IntSort> nrOfHarvestedClustersC = ctx.mkAdd(clusterBoolMap.entrySet().stream()
                .map(e -> ctx.mkITE(
                        ctx.mkAnd(e.getValue(), ctx.mkOr(blockPosPlanePosMap.get(e.getKey()).stream()
                                .map(planePosBoolMap::get)
                                .toArray(BoolExpr[]::new))),
                        ctx.mkInt(1),
                        ctx.mkInt(0)
                ))
                .filter(IntExpr.class::isInstance) // You'd think that these two lines prevent the compiler from
                .map(IntExpr.class::cast)          // complaining, but it only shuts up the IDE.
                .toArray(IntExpr[]::new));

        nrOfProjections = ctx.mkIntConst("nr_of_projections");
        ArithExpr<IntSort> nrOfProjectionsC = ctx.mkAdd(planePosBoolMap.values().stream()
            .map(planePos -> ctx.mkITE(planePos, ctx.mkInt(1), ctx.mkInt(0)))
            .filter(IntExpr.class::isInstance) // You'd think that these two lines prevent the compiler from
            .map(IntExpr.class::cast)          // complaining, but it only shuts up the IDE.
            .toArray(IntExpr[]::new));

        solver.add(ctx.mkEq(nrOfHarvestedClusters, nrOfHarvestedClustersC));
        solver.add(ctx.mkEq(nrOfProjections, nrOfProjectionsC));
    }


    public void buildSolver() {
        buildBudClusterRelations();
        buildProjectionRelations();
        buildMetrics();

        sendFeedbackOrLog("Constraints built!");
    }

    @SafeVarargs
    private Status solveTimeout(int seconds, Expr<BoolSort>... assumptions) {
        Params params = ctx.mkParams();
        params.add("timeout", (int)TimeUnit.SECONDS.toMillis(seconds));
        solver.setParameters(params);

        Status status = solver.check(assumptions);
        if (status == Status.SATISFIABLE) {
            model = solver.getModel();
        }
        return status;
    }


    public void solve() throws TimeoutException {
        // For the starting target for the number of harvested clusters, we determine how many clusters can naively be
        // harvested without destroying any buds
        // NOTE: Instead of trying to beat the naive target right away, we start with exactly the number of
        //       naively harvested cluster. This guarantees we always get a model to work with.
        //       We have to filter out 1x1 holes, else the naive solution can be an overestimation
        int minimum_harvested_clusters = (int)slices.stream()
                .filter(slice -> planePosBlockPosMap.get(slice).stream().noneMatch(buddingAmethysts::contains)) // Filter out slices with buds in them
                .filter(slice -> !slice.neighbours() // Filter out slices that are blocked in by their neighbours
                    .allMatch(neighbour -> slices.contains(neighbour)
                            && planePosBlockPosMap.get(neighbour).stream().anyMatch(buddingAmethysts::contains)))
                .flatMap(slice -> planePosBlockPosMap.get(slice).stream())  // for the remaining slices, add all the blocks (clusters) they clear
                .distinct() // filter out duplicate blocks
                .count();
        for (;; minimum_harvested_clusters++) {
            int finalMinimum_harvested_clusters = minimum_harvested_clusters;
            Status status = solveTimeout(20, ctx.mkLe(ctx.mkInt(finalMinimum_harvested_clusters), nrOfHarvestedClusters));

            if (status == Status.UNKNOWN) {
                sendFeedbackOrLog("Optimizing cluster yield timed out!");
                break;
            } else if (status == Status.UNSATISFIABLE) {
                sendFeedbackOrLog("Fully optimized cluster yield!");
                break;
            }

            minimum_harvested_clusters = Integer.parseInt(model.eval(nrOfHarvestedClusters, false).getSExpr());
            sendFeedbackOrLog("nr of harvested clusters: %d", minimum_harvested_clusters);
        }

        if (model == null) {
            throw new TimeoutException(
                    "Initial projection timed out. " +
                    "Rerun the command with a larger cluster optimization timeout, " +
                    "or if you selected multiple geodes at once, select one geode at a time.");
        }
        minimum_harvested_clusters = Integer.parseInt(model.eval(nrOfHarvestedClusters, false).getSExpr());
        solver.add(ctx.mkEq(ctx.mkInt(minimum_harvested_clusters), nrOfHarvestedClusters));

        int maximum_projections = Integer.parseInt(model.eval(nrOfProjections, false).getSExpr()) - 1;
        sendFeedbackOrLog("nr of projections: " + maximum_projections);

        for (;;maximum_projections--) {
            int finalMaximum_projections = maximum_projections;
            Status status = solveTimeout(5, ctx.mkGe(ctx.mkInt(finalMaximum_projections), nrOfProjections));

            if (status == Status.UNKNOWN) {
                sendFeedbackOrLog("Minimizing the projections timed out!");
                break;
            } else if (status == Status.UNSATISFIABLE) {
                sendFeedbackOrLog("Fully minimized the number of projections!");
                break;
            }

            maximum_projections = Integer.parseInt(model.eval(nrOfProjections, false).getSExpr());
            sendFeedbackOrLog("nr of projections: %d", maximum_projections);
        }
    }

    public void postProcess() {
        // We minimized the number of active slices.
        // Since slices are a boolean, that means that we got more blocked slices (obsidian) as a result.
        // For these blocked slices, we can remove them if they don't intersect with any active budding amethysts
        planePosBoolMap = planePosBoolMap.entrySet().stream()
            .filter(entry ->
                switch (model.eval(entry.getValue(), false).getBoolValue()) {
                    case Z3_L_TRUE -> true;
                    case Z3_L_UNDEF -> false;
                    case Z3_L_FALSE -> planePosBlockPosMap.get(entry.getKey()).stream()
                                       .filter(buddingAmethysts::contains)
                                       .anyMatch(geodesyBlockPos -> model.eval(budBoolMap.get(geodesyBlockPos), false)
                                                                    .getBoolValue() == Z3_lbool.Z3_L_TRUE);})
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public void removeNaivelyHarvestedClustersFromWorld() {
        slices.stream()
                .filter(slice -> planePosBlockPosMap.get(slice).stream().noneMatch(buddingAmethysts::contains)) // Filter out slices with buds in them
                .filter(slice -> !slice.neighbours() // Filter out slices that are blocked in by their neighbours
                        .allMatch(neighbour -> slices.contains(neighbour)
                                && planePosBlockPosMap.get(neighbour).stream().anyMatch(buddingAmethysts::contains)))
                .flatMap(slice -> planePosBlockPosMap.get(slice).stream())  // for the remaining slices, add all the blocks (clusters) they clear
                .distinct() // filter out duplicate blocks
            .forEach(blockPos -> core.getWorld().setBlockState(blockPos.toBlockPos(), Blocks.AIR.getDefaultState())); // set the blocks to air
    }

    public void removeSatHarvestedClustersFromWorld() {
        planePosBoolMap.entrySet().stream()
            .filter(entry -> model.eval(entry.getValue(), false).getBoolValue() == Z3_lbool.Z3_L_TRUE)
            .flatMap(entry -> planePosBlockPosMap.get(entry.getKey()).stream())
            .distinct()
            .forEach(blockPos -> core.getWorld().setBlockState(blockPos.toBlockPos(), Blocks.AIR.getDefaultState()));
    }

    public void applyModelToWorld(Collection<Direction> directions) {
        if (model == null) {
            core.sendCommandFeedback("No model to paste in the world!");
            return;
        }
        sendFeedbackOrLog("Pasting model in the world");

        Map<GeodesyBlockPos, Z3_lbool> budResultMap = budBoolMap.entrySet().stream().collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> model.eval(entry.getValue(), false).getBoolValue()));

        Map<GeodesyBlockPos, Z3_lbool> clusterResultMap = clusterBoolMap.entrySet().stream().collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> model.eval(entry.getValue(), false).getBoolValue()));

        Map<GeodesyPlanePos, Z3_lbool> sliceResultMap = planePosBoolMap.entrySet().stream().collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> model.eval(entry.getValue(), false).getBoolValue()));


        clusterResultMap.forEach((blockPos, bool) -> {
                BlockState blockState = switch (bool) {
                    case Z3_L_TRUE -> Blocks.GLASS.getDefaultState();
                    case Z3_L_FALSE -> Blocks.TINTED_GLASS.getDefaultState();
                    case Z3_L_UNDEF -> Blocks.OAK_LEAVES.getDefaultState().with(Properties.PERSISTENT, true);
                };
                core.getWorld().setBlockState(blockPos.toBlockPos(), blockState);
            });

        budResultMap.entrySet().stream()
                .filter(entry -> entry.getValue() == Z3_lbool.Z3_L_TRUE)
                .forEach(entry -> core.getWorld().setBlockState(entry.getKey().toBlockPos(), Blocks.BUDDING_AMETHYST.getDefaultState()));

        // Determine the wall offsets
        Map<Direction, Integer> wall_offsets = directions.stream().collect(Collectors.toMap(Function.identity(),
            direction -> {
                Stream<Integer> dirCoords = blockPosPlanePosMap.keySet().stream().map(blockPos -> blockPos.choose(direction.getAxis()));
                return direction.getDirection() == Direction.AxisDirection.POSITIVE
                    ? dirCoords.max(Integer::compare).orElseThrow() + GeodesyCore.WALL_OFFSET
                    : dirCoords.min(Integer::compare).orElseThrow() - GeodesyCore.WALL_OFFSET;
                }));

        // For all directions, for all slices where the direction matches, determine the blocks & offsets and place them
        wall_offsets.forEach((direction, offset_coord) ->
            sliceResultMap.entrySet().stream()
                .filter(entry -> entry.getKey().plane() == direction.getAxis())
                .forEach(entry -> {
                    GeodesyPlanePos slice = entry.getKey();
                    Z3_lbool bool = entry.getValue();
                    BlockState block = switch (bool) {
                        case Z3_L_TRUE -> Blocks.PUMPKIN.getDefaultState();
                        case Z3_L_FALSE -> Blocks.OBSIDIAN.getDefaultState();
                        case Z3_L_UNDEF -> Blocks.OAK_LEAVES.getDefaultState().with(Properties.PERSISTENT, true);
                    };
                    int[] posOffset = switch(slice.plane()) {
                        case X -> new int[]{offset_coord, 0, 0};
                        case Y -> new int[]{0, offset_coord, 0};
                        case Z -> new int[]{0, 0, offset_coord};
                    };
                core.getWorld().setBlockState(slice.toBlockPos(posOffset[0], posOffset[1], posOffset[2]), block);
            }));
    }

    private void sendFeedbackOrLog(String message, Object... args) {
        String processedMessage = String.format(message, args);
        if (core == null) {
            LOGGER.info(processedMessage);
        } else {
            core.sendCommandFeedback(processedMessage);
        }
    }

    public static void main(String[] args) throws TimeoutException {
        Projection proj = new Projection();
        proj.buildSolver();
        proj.solve();
        proj.postProcess();
    }

}
