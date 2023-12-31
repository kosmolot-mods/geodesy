package pl.kosma.geodesy.utils;

import java.util.Iterator;
import java.util.List;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.stream.Collectors;

// Class taken from https://stackoverflow.com/a/71999635
public class NestedListIterator<T> implements Iterator<T> {
    private final List<Iterator<T>> iterators;
    private final ListIterator<Iterator<T>> listIterator;

    public NestedListIterator(List<List<T>> nestedList) {
        this.iterators = nestedList.stream()
                .map(List::iterator)
                .collect(Collectors.toCollection(LinkedList::new));

        this.listIterator = iterators.listIterator();
    }

    @Override
    public boolean hasNext() {
        return iterators.stream().anyMatch(Iterator::hasNext);
    }

    @Override
    public T next() {
        if (!iterators.isEmpty() && !listIterator.hasNext()) tryReset();

        while (!iterators.isEmpty() && listIterator.hasNext()) {
            Iterator<T> current = listIterator.next();

            if (!current.hasNext()) {
                listIterator.remove(); // removing exhausted iterator
            } else {
                return current.next();
            }

            if (!listIterator.hasNext()) tryReset();
        }
        throw new IllegalStateException();
    }

    private void tryReset() {
        while (listIterator.hasPrevious()) {
            listIterator.previous();
        }
    }
}