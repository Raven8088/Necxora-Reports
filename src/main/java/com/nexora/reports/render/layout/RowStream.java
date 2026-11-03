package com.nexora.reports.render.layout;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;

/**
 * The rows of a sheet as a re-iterable concatenation of fixed rows and of rows mapped lazily from a result's data:
 * a sheet with a million cells is never held as layout objects, only produced while it is written (D-B7-3).
 */
final class RowStream implements Iterable<LayoutRow> {

    private final List<Iterable<LayoutRow>> parts = new ArrayList<>();
    private List<LayoutRow> open;
    private int size;

    RowStream add(LayoutRow row) {
        if (open == null) {
            open = new ArrayList<>();
            parts.add(open);
        }
        open.add(row);
        size++;
        return this;
    }

    <T> RowStream addAll(List<T> source, Function<T, LayoutRow> mapping) {
        open = null;
        parts.add(() -> new Iterator<>() {
            private final Iterator<T> it = source.iterator();

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public LayoutRow next() {
                return mapping.apply(it.next());
            }
        });
        size += source.size();
        return this;
    }

    /** The number of rows, so far: the index the next row will have. */
    int size() {
        return size;
    }

    @Override
    public Iterator<LayoutRow> iterator() {
        Iterator<Iterable<LayoutRow>> outer = parts.iterator();
        return new Iterator<>() {
            private Iterator<LayoutRow> inner = java.util.Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                while (!inner.hasNext() && outer.hasNext()) {
                    inner = outer.next().iterator();
                }
                return inner.hasNext();
            }

            @Override
            public LayoutRow next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return inner.next();
            }
        };
    }
}
