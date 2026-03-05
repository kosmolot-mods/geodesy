package pl.kosma.geodesy.solver;

/**
 * Interface for face solving algorithms.
 * Takes a FaceGrid and computes optimal slime/honey block placement.
 */
@FunctionalInterface
public interface FaceSolver {
    SolverResult solve(FaceGrid input, SolverConfig config);
}
