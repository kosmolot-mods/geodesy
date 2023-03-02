package pl.kosma.geodesy.projection;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;

import com.microsoft.z3.*;
import com.microsoft.z3.enumerations.Z3_lbool;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.world.World;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Projection {

    private Set<GeodesyBlockPos> buddingAmethysts;
    private Set<GeodesyBlockPos> amethystClusters;
    private Set<GeodesyPlanePos> slices;

    private Map<GeodesyBlockPos, BoolExpr> budBoolMap;
    private Map<GeodesyBlockPos, BoolExpr> clusterBoolMap;
    private Map<GeodesyPlanePos, BoolExpr> planePosBoolMap;

    private Map<GeodesyBlockPos, Set<GeodesyPlanePos>> blockPosPlanePosMap;

    private Context ctx;
    private Solver solver;

    private IntExpr nrOfHarvestedClusters;
    private IntExpr nrOfProjections;

    public Projection () {
        initGeode();
        initZ3();
    }

    private void initGeode() {
        // TODO: connect this to an actual source of geodes instead of ExampleGeode
        this.buddingAmethysts = ExampleGeode.buddingAmethysts;

        // Generate potential cluster locations from the buds
        // NOTE: We intentionally leave in positions that overlap with budding amethysts.
        this.amethystClusters = buddingAmethysts.stream()
            .flatMap(GeodesyBlockPos::neighbours)
            .collect(Collectors.toSet());

        // For all buds, clusters, and planes, generate the slices and a mapping from blockPos to planePos
        this.slices = new HashSet<>();
        this.blockPosPlanePosMap = new HashMap<>();
        for (PlaneEnum plane : PlaneEnum.values()) {
            for (GeodesyBlockPos blockPos : Stream.of(buddingAmethysts, amethystClusters).flatMap(Set::stream).toList()) {
                GeodesyPlanePos planePos = GeodesyPlanePos.fromBlockPos(blockPos, plane);
                slices.add(planePos);
                blockPosPlanePosMap.computeIfAbsent(blockPos, k -> new HashSet<>()).add(planePos);
            }
        }
    }

    private void initZ3() {
        this.ctx = new Context();
        this.solver = ctx.mkSolver();
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
                .filter(planePos -> planePos.plane() == PlaneEnum.Y)
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
            if (planePos.plane() == PlaneEnum.Y) {
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
            .filter(planePos -> planePos.plane() != PlaneEnum.Y)
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



    public void buildSolver(CommandContext<ServerCommandSource> context) {
        buildBudClusterRelations();
        buildProjectionRelations();
        buildMetrics();

        // We enter with null if we launch from this class instead of from Minecraft.
        if (context != null) {
            context.getSource().sendFeedback(Text.of("Constraints built!"), false);
        }
    }

    public Model solve(CommandContext<ServerCommandSource> context) {
        // TODO: Create a more optimal and less arbitrary strategy to quickly find the limits of each problem
        int minimum_harvested_clusters = 350;
        Model model = null;
        while (true) {
            if (solver.check(ctx.mkLe(ctx.mkInt(minimum_harvested_clusters), nrOfHarvestedClusters)) == Status.SATISFIABLE) {
                model = solver.getModel();
                minimum_harvested_clusters = Integer.parseInt(model.eval(nrOfHarvestedClusters, false).getSExpr());
                String harvest_str = "nr of harvested clusters: " + model.eval(nrOfHarvestedClusters, false).getSExpr();
                if (context == null) {
                    System.out.println(harvest_str);
                } else {
                    context.getSource().sendFeedback(Text.of(harvest_str), false);
                }
                minimum_harvested_clusters += 1;
            } else {
                System.out.println("Unsat!");
                break;
            }
        }

        if (model == null) {
            System.out.println("Aborting projection.");
            return null;
        }
        minimum_harvested_clusters = Integer.parseInt(model.eval(nrOfHarvestedClusters, false).getSExpr());
        solver.add(ctx.mkEq(ctx.mkInt(minimum_harvested_clusters), nrOfHarvestedClusters));


        int maximum_projections = Integer.parseInt(model.eval(nrOfProjections, false).getSExpr()) - 1;
        System.out.println("nr of projections: " + maximum_projections);
        while (maximum_projections > 215) {
            if (solver.check(ctx.mkGe(ctx.mkInt(maximum_projections), nrOfProjections)) == Status.SATISFIABLE) {
                model = solver.getModel();
                maximum_projections = Integer.parseInt(model.eval(nrOfProjections, false).getSExpr());
                String projection_str = "nr of projections: " + model.eval(nrOfProjections, false).getSExpr();
                if (context == null) {
                    System.out.println(projection_str);
                } else {
                    context.getSource().sendFeedback(Text.of(projection_str), false);
                }
                maximum_projections -= 1;
            } else {
                System.out.println("Unsat!");
                break;
            }
        }
        return model;
    }

    public void applyModelToWorld(Model model, CommandContext<ServerCommandSource> context) {
        if (model == null) {
            context.getSource().sendFeedback(Text.of("No model to paste in the world!"), false);
            return;
        }
        context.getSource().sendFeedback(Text.of("Pasting model in the world"), false);

        System.out.println("Sat! Model:\n" + solver.getModel().eval(nrOfHarvestedClusters, false));

        Map<GeodesyBlockPos, Z3_lbool> budResultMap = budBoolMap.entrySet().stream().collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> model.eval(entry.getValue(), false).getBoolValue()));

        Map<GeodesyBlockPos, Z3_lbool> clusterResultMap = clusterBoolMap.entrySet().stream().collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> model.eval(entry.getValue(), false).getBoolValue()));

        Map<GeodesyPlanePos, Z3_lbool> sliceResultMap = planePosBoolMap.entrySet().stream().collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> model.eval(entry.getValue(), false).getBoolValue()));

        World world = context.getSource().getPlayer().getWorld();

        budResultMap.entrySet().stream()
            .filter(entry -> entry.getValue() == Z3_lbool.Z3_L_TRUE)
            .forEach(entry -> world.setBlockState(entry.getKey().toBlockPos(), Blocks.BUDDING_AMETHYST.getDefaultState()));
        clusterResultMap.entrySet().stream()
                .filter(entry -> entry.getValue() == Z3_lbool.Z3_L_TRUE)
                .forEach(entry -> world.setBlockState(entry.getKey().toBlockPos(), Blocks.GLASS.getDefaultState()));

        sliceResultMap.forEach((key, value) -> {
            BlockState block = switch (value) {
                case Z3_L_TRUE -> Blocks.PUMPKIN.getDefaultState();
                case Z3_L_FALSE -> Blocks.OBSIDIAN.getDefaultState();
                case Z3_L_UNDEF -> Blocks.OAK_LEAVES.getDefaultState().with(Properties.PERSISTENT, true);
            };
            int[] posOffset = switch(key.plane()) {
                case X -> new int[]{-3, 0, 0};
                case Y -> new int[]{0, 20, 0};
                case Z -> new int[]{0, 0, -3};
            };
            world.setBlockState(key.toBlockPos(posOffset[0], posOffset[1], posOffset[2]), block);
        });
    }

    public static void main(String[] args) {
        Projection proj = new Projection();
        // Passing null makes it crash once it tries to interact with Minecraft, but if we launch it from here
        // we don't have a Minecraft instance anyway
        proj.buildSolver(null);
        proj.solve(null);
    }

}
