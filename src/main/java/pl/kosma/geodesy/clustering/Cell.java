package pl.kosma.geodesy.clustering;

import org.fusesource.jansi.Ansi;

public abstract class Cell {
    int row;
    int col;

    Cell(int row, int col) {
        this.row = row;
        this.col = col;
    }

    /**
     * Yields an ANSI coloring representation of this cell in its unclustered state.
     */
    abstract Ansi toAnsiUnclustered();

}
