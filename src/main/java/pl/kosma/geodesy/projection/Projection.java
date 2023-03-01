package pl.kosma.geodesy.projection;

import com.microsoft.z3.*;

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
                    "amethyst_crystal__" + cluster.x() + "__" + cluster.y() + "__" + cluster.z())));
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
        //# We have four relations to define:
        //# Relation 1: Active slices lead to inactive budding amethysts
        //# Relation 2: Active buds lead to inactive slices
        //# Relation 3: 1x1 holes in the vertical (y) axis cannot exist.
        //# Relation 4: 1x1 holes in the horizontal (x, z) axes can exist in specific scenarios
        //###############################################################################################

        // Set relations 1 and 2 between slices and budding amethysts:
        // When a slice is active, the budding amethysts that intersect with it are inactive
        // When a budding amethyst is active, no slice that could harvest it is active
        Stream<BoolExpr> budSliceImplicationsC = blockPosPlanePosMap.entrySet().stream()
            .filter(e -> buddingAmethysts.contains(e.getKey()))
            .flatMap(e -> e.getValue().stream()
                .map(planePos -> {
                    BoolExpr planePosBool = planePosBoolMap.get(planePos);
                    BoolExpr budBool = budBoolMap.get(e.getKey());
                    return ctx.mkAnd(ctx.mkImplies(planePosBool, ctx.mkNot(budBool)),
                                     ctx.mkImplies(ctx.mkNot(planePosBool), budBool));
                    }));

        // For relations 3 and 4, we need to identify potential 1x1 holes first:
        Set<GeodesyPlanePos> potentialOneByOneHoles = slices.stream()
                .filter(slice -> slice.neighbours().allMatch(slices::contains))
                .collect(Collectors.toSet());

        //# Map the holes to a set of up to three projections that must be active to make it possible to power it
        //# The following holes allow for the projection to be active
        //#     B
        //#     B
        //#   AA#CC
        //#    #H#
        //#     #
        //# Where # is blocked, H is the hole, and A, B, or C has to be free
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

        // Set 1x1 hole prevention for the vertical (y) plane:
        // A potential hole being active implies that at least one of its neighbours is also active,
        // because then it's not a 1x1 hole but at least 2x1.
        Stream<BoolExpr> blockVerticalOneByOneHolesC = potentialOneByOneHoles.stream()
            .filter(planePos -> planePos.plane() == PlaneEnum.Y)
            .map(planePos -> ctx.mkImplies(
                planePosBoolMap.get(planePos),
                ctx.mkOr(planePos.neighbours()
                         .map(planePosBoolMap::get)
                         .toArray(BoolExpr[]::new))));

        // Set 1x1 hole prevention for the horizontal (x, z) planes:
        // A potential hole being active while its neighbours are inactive requires at least one of the sets to be fully
        // active so the original hole can be powered.
        Stream<BoolExpr> blockSpecificHorizontalOneByOneHolesC = potentialOneByOneHoles.stream()
            .filter(planePos -> planePos.plane() != PlaneEnum.Y)
            .map(planePos -> ctx.mkImplies(
                ctx.mkAnd(planePosBoolMap.get(planePos),
                    ctx.mkAnd(planePos.neighbours().map(planePosBoolMap::get).toArray(BoolExpr[]::new))),
                ctx.mkOr(potentialHolesToRequiredProjectionSets.get(planePos).stream()
                    .map(required_active_group -> ctx.mkAnd(required_active_group.toArray(BoolExpr[]::new)))
                    .toArray(BoolExpr[]::new))
            ));

        this.solver.add(budSliceImplicationsC.toArray(BoolExpr[]::new));
        this.solver.add(blockVerticalOneByOneHolesC.toArray(BoolExpr[]::new));
        this.solver.add(blockSpecificHorizontalOneByOneHolesC.toArray(BoolExpr[]::new));
    }
    public void buildSolver() {
        buildBudClusterRelations();
        buildProjectionRelations();

        IntExpr nrOfHarvestedClusters = ctx.mkIntConst("nr_of_harvested_clusters");
        // TODO: Figure out how to make the below expression sound in terms of typing
        // It works in practice, but in terms of typing it dies.
        ArithExpr<IntSort> nrOfHarvestedClustersC = ctx.mkAdd(clusterBoolMap.entrySet().stream()
                .map(e -> ctx.mkITE(
                        ctx.mkAnd(e.getValue(), ctx.mkOr(blockPosPlanePosMap.get(e.getKey()).stream()
                                .map(planePosBoolMap::get)
                                .toArray(BoolExpr[]::new))),
                        ctx.mkInt(1),
                        ctx.mkInt(0)
                ))
                .filter(IntExpr.class::isInstance) // You'd think that these two lines prevent the compiler from
                .map(IntExpr.class::cast)          // complaining, but it only shuts up the compiler.
                .toArray(IntExpr[]::new));
        //var b = ctx.mkAdd(nrOfHarvestedClustersC.stream().map(toArray(ArithExpr[]::new));
        solver.add(ctx.mkEq(nrOfHarvestedClusters, nrOfHarvestedClustersC));

        // TODO: Figure out why the results differ from the Python implementation and why it is eons faster
        // TODO: than the Python version despite both versions of the library being a wrapper around the same c++ binary
        // Currently, 360 clusters is satisfiable and 361 is unsat
        if (solver.check(ctx.mkLe(ctx.mkInt(360), nrOfHarvestedClusters)) == Status.SATISFIABLE) {
            System.out.println("Sat! Model:\n" + solver.getModel());
        } else {
            System.out.println("Unsat!");
        }
    }

    public static void main(String[] args) {
        Projection proj = new Projection();
        proj.buildSolver();
    }

}
