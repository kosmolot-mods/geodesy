package pl.kosma.geodesy.clustering;

import org.fusesource.jansi.Ansi;

import static org.fusesource.jansi.Ansi.ansi;

public final class SetCell extends Cell {
    SetCell(int row, int col) {
        super(row, col);
    }

    @Override
    Ansi toAnsiUnclustered() {
        return ansi().bg(Ansi.Color.GREEN).a("@@").reset();
    }
}
