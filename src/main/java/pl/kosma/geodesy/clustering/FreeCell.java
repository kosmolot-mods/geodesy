package pl.kosma.geodesy.clustering;

import org.fusesource.jansi.Ansi;

import static org.fusesource.jansi.Ansi.*;
public final class FreeCell extends Cell {

    FreeCell(int row, int col) {
        super(row, col);
    }

    @Override
    Ansi toAnsiUnclustered() {
        return ansi().bg(Color.YELLOW).a("++").reset();
    }

}
