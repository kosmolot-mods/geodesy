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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.function.Predicate.not;

public class Projection {
    private static final Logger LOGGER = LoggerFactory.getLogger(Projection.class.getCanonicalName());
    private GeodesyCore core;
    private Set<GeodesyBlockPos> buddingAmethysts;
    private Set<GeodesyBlockPos> amethystClusters;
    private Set<GeodesyPlanePos> slices;

    private Set<GeodesyBlockPos> naivelyHarvestableClusters;
    private Map<GeodesyPlanePos, Long> sliceRemovalLossMap;
    private Map<GeodesyPlanePos, Long> sliceRemovalGainMap;

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
        initBudProperties();
        initZ3();
    }
    public Projection (GeodesyCore core, List<BlockPos> buddingAmethystPositions) {
        this.core = core;
        initGeode(buddingAmethystPositions);
        initBudProperties();
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

    private void initBudProperties() {
        // Determine all budless slices which are surrounded by slices with buds in them
        // For the X and Z direction, some machines can power such slices given a certain pattern, so for
        // slices with that pattern, remove them from the set.
        Set<GeodesyPlanePos> naiveOneByOneHoles = slices.stream()
            .filter(slice -> planePosBlockPosMap.get(slice).stream() // For all slices:
                .noneMatch(buddingAmethysts::contains))              // Discard slices with buds.
            .filter(slice -> slice.neighbours()                      // Keep slices where:
                .allMatch(neighbour ->                               // All neighbours have an
                    slices.contains(neighbour)                       // actual slice,
                    && planePosBlockPosMap.get(neighbour).stream()   //
                        .anyMatch(buddingAmethysts::contains)))      // and they contain budding amethysts.
            .filter(slice ->
                slice.plane() == Direction.Axis.Y                    // Keep slices when they are in the Y direction,
                ||                                                   // or keep them when:
                Stream.of(Set.of(List.of(1, -1), List.of(1, -2)),                                  // For all offset locations:
                          Set.of(List.of(2, 0), List.of(3, 0)),                                    //
                          Set.of(List.of(1, 1), List.of(1, 2)))                                    //
                    .noneMatch(offsetSet -> offsetSet.stream()                                     // For none of the sets of
                        .map(offset -> slice.offsetHorizontalPlane(offset.get(0), offset.get(1)))  // offset slices
                        .filter(slices::contains)                                                  // that are real,
                        .allMatch(offsetSlice -> planePosBlockPosMap.get(offsetSlice)              // all slices within the sets
                            .stream().noneMatch(buddingAmethysts::contains))))                     // should have no buds in them
            .collect(Collectors.toSet());

        // Determine naively harvestable slices, that is: the slices without buds in them
        Set<GeodesyPlanePos> naivelyHarvestableSlices = slices.stream()
                .filter(slice -> planePosBlockPosMap.get(slice).stream()
                        .noneMatch(buddingAmethysts::contains))
                .collect(Collectors.toSet());

        // Determine the naively harvestable clusters: the clusters that can be harvested without breaking buds
        naivelyHarvestableClusters = naivelyHarvestableSlices.stream()
            .filter(slice -> !naiveOneByOneHoles.contains(slice))       // Filter out slices that are blocked in
            .flatMap(slice -> planePosBlockPosMap.get(slice).stream())  // Map the slices to the blocks (clusters) they clear
            .collect(Collectors.toSet());                               // Filter out duplicate blocks

        // Determine for each slice how many naively harvestable clusters would be lost if the buds of the slice
        // were destroyed.
        sliceRemovalLossMap = slices.stream().collect(Collectors.toMap(
            Function.identity(),
            slice -> {
                Set<GeodesyBlockPos> sliceBuds = planePosBlockPosMap.get(slice);

                return sliceBuds.stream()                         // For all buds in the slice,
                    .flatMap(GeodesyBlockPos::neighbours)         // map to their neighbours
                    .distinct()                                   // and filter out duplicates.
                    .filter(amethystClusters::contains)           // Keep only the clusters.
                    .filter(not(buddingAmethysts::contains))      // Remove clusters at bud locations
                    .filter(naivelyHarvestableClusters::contains) // Keep only naively harvestable clusters
                    .filter(cluster -> cluster.neighbours()       // Keep the clusters that have at least
                        .filter(buddingAmethysts::contains)       // one neighbour bud
                        .anyMatch(not(sliceBuds::contains)))      // other than the buds in the slice that we remove
                    .count();
            }
        ));

        // Determine for each slice how many non-naively harvestable clusters could be gained by destroying the buds
        // in that slice.
        // This does not (yet) account for slices that could be freed as a result of quasi-connectivity
        sliceRemovalGainMap = slices.stream().collect(Collectors.toMap(
            Function.identity(),
            slice -> {
                Set<GeodesyBlockPos> sliceBuds = planePosBlockPosMap.get(slice);

                // Get all slices that have been freed by removing the sliceBuds
                var directlyFreedSlices = sliceBuds.stream()                                // For all buds in the main slice
                    .flatMap(bud -> blockPosPlanePosMap.get(bud).stream())                  // map to the buds' slices
                    .distinct()
                    .filter(changedSlice -> planePosBlockPosMap.get(changedSlice).stream()  // Keep slices only when
                        .filter(buddingAmethysts::contains)                                 // it only has buds
                        .noneMatch(not(sliceBuds::contains)))                               // that we remove
                    .collect(Collectors.toSet());

                // Get all one by one hole slices that have been freed by freeing one of its neighbours
                // NOTE: This does not account for freeing a slice through quasi-connectivity
                var freedHoleSlices = naiveOneByOneHoles.stream()
                    .filter(hole -> hole.neighbours().anyMatch(directlyFreedSlices::contains))
                    .collect(Collectors.toSet());

                return Stream.of(directlyFreedSlices, freedHoleSlices).flatMap(Set::stream)
                    .distinct()
                    .flatMap(freedSlice -> planePosBlockPosMap.get(freedSlice).stream())
                    .distinct()
                    .filter(amethystClusters::contains)                 // Keep only the clusters
                    .filter(not(naivelyHarvestableClusters::contains))  // that are not naively harvestable
                    .filter(cluster ->                                  // Keep the clusters,
                        cluster.neighbours()                            // that have at least one
                        .filter(buddingAmethysts::contains)             // bud neighbour that
                        .anyMatch(not(sliceBuds::contains)))            // is not a slice bud
                    .count();
            }
        ));
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
            .map(blockPos -> ctx.mkNot(ctx.mkAnd(clusterBoolMap.get(blockPos), budBoolMap.get(blockPos))));

        this.solver.add(clusterImpliesNeighbourBudC.toArray(BoolExpr[]::new));
        this.solver.add(budImpliesPossibleNeighbourClusterC.toArray(BoolExpr[]::new));
        this.solver.add(budXorClusterC.toArray(BoolExpr[]::new));
    }

    private void buildProjectionRelations() {
        //###############################################################################################
        //# Set projection relations
        //# We have three relations to define:
        //# Relation 1: A slice or at least one of its buds can be active, but not both
        //# Relation 2: 1x1 holes in the vertical (y) plane cannot exist
        //# Relation 3: 1x1 holes in the horizontal (x, z) planes can exist in specific scenarios
        //###############################################################################################

        // Set relation 1: A slice or at least one of its buds can be active, but not both
        // With quite a bit of preprocessing, we limit the slices that we pass to the SAT solver.
        // Only when a slice with a bud in it can lead to a net gain, we allow the SAT solver control of the
        // relevant slices and buds.
        // In other cases, we set the slices to false and the buds to true
        Stream<BoolExpr> sliceNandAnyBudsC = slices.stream()
            .filter(slice -> planePosBlockPosMap.get(slice).stream().anyMatch(buddingAmethysts::contains))
            .map(slice -> {
                // Get the maximum netGain from all slices that interact with any of the buds in this slice
                Long maxNetGain = planePosBlockPosMap.get(slice).stream()
                    .flatMap(bud -> blockPosPlanePosMap.get(bud).stream())
                    .map(indirectSlice -> sliceRemovalGainMap.get(indirectSlice) - sliceRemovalLossMap.get(indirectSlice))
                    .max(Long::compare).orElseThrow(); // Since we filtered out budless slices before, we never throw here

                BoolExpr sliceBudC;
                if (maxNetGain > 0) {
                    sliceBudC = ctx.mkNot(ctx.mkAnd(
                        planePosBoolMap.get(slice),
                        ctx.mkOr(planePosBlockPosMap.get(slice).stream()
                            .filter(buddingAmethysts::contains)
                            .map(budBoolMap::get)
                            .toArray(BoolExpr[]::new))));
                } else {
                    // If there is never a gain for any of the slices of the buds, then there is no gain for the buds either
                    // Hence, we can disable the slices and activate the buds
                    sliceBudC = ctx.mkAnd(
                        ctx.mkEq(planePosBoolMap.get(slice), ctx.mkFalse()),
                        // This part here will have ton of duplicates: one for each slice, but we don't care for the time being
                        // The SAT solver is perfectly equipped to handle such duplicates
                        ctx.mkAnd(
                            planePosBlockPosMap.get(slice).stream()
                                .filter(buddingAmethysts::contains)
                                .map(budBoolMap::get)
                                .map(budBool -> ctx.mkAnd(budBool, ctx.mkTrue()))
                                .toArray(BoolExpr[]::new))
                    );
                }
                return sliceBudC;
            }
        );

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
                Stream.of(Set.of(List.of(1, -1), List.of(1, -2)),
                          Set.of(List.of(2, 0), List.of(3, 0)),
                          Set.of(List.of(1, 1), List.of(1, 2)))
                .map(offsetSet -> offsetSet.stream()
                    .map(offset -> planePos.offsetHorizontalPlane(offset.get(0), offset.get(1)))
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

        this.solver.add(sliceNandAnyBudsC.toArray(BoolExpr[]::new));
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
        // For the starting target for the number of harvested clusters, we use the number of naively harvestable clusters
        // Instead of trying to beat the naive target right away, we start with exactly the number of
        // naively harvested cluster. This guarantees we always get a model to work with.
        int minimum_harvested_clusters = naivelyHarvestableClusters.size();
        sendFeedbackOrLog("Without breaking buds, %d clusters can be harvested", minimum_harvested_clusters);
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
        sendFeedbackOrLog("By breaking budding amethysts, %d extra clusters can be harvested!",
                minimum_harvested_clusters - naivelyHarvestableClusters.size());
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
        naivelyHarvestableClusters
            .forEach(blockPos -> core.getWorld().setBlockState(blockPos.toBlockPos(), Blocks.AIR.getDefaultState()));
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
