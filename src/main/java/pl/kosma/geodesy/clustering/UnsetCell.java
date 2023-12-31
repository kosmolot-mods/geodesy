package pl.kosma.geodesy.clustering;

import org.fusesource.jansi.Ansi;

import static org.fusesource.jansi.Ansi.ansi;

public final class UnsetCell extends Cell {
    UnsetCell(int row, int col) {
        super(row, col);
    }

    @Override
    Ansi toAnsiUnclustered() {
        return ansi().bg(Ansi.Color.BLACK).a("##").reset();
    }
}
