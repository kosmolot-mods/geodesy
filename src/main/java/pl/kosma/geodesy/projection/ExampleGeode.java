package pl.kosma.geodesy.projection;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ExampleGeode {

    // Compressed format describing only the coordinates of the budding amethysts
    static Map<Integer, List<List<Integer>>> geodeCompressed = Map.ofEntries(
            Map.entry(0, List.of()),
            Map.entry(1, List.of(List.of(10, 9), List.of(7, 6))),
            Map.entry(2, List.of(List.of(13, 8), List.of(12, 4), List.of(11, 9),
                    List.of(9, 9), List.of(8, 11), List.of(8, 9), List.of(7, 9),
                    List.of(7, 6), List.of(6, 9), List.of(5, 4))),
            Map.entry(3, List.of(List.of(14, 8), List.of(11, 3), List.of(10, 12),
                    List.of(9, 11), List.of(9, 4), List.of(9, 2), List.of(7, 12),
                    List.of(7, 10), List.of(6, 12), List.of(6, 5), List.of(5, 8),
                    List.of(4, 9), List.of(3, 8))),
            Map.entry(4, List.of(List.of(13, 4), List.of(9, 2), List.of(5, 12),
                    List.of(3, 5), List.of(2, 4))),
            Map.entry(5, List.of(List.of(14, 11), List.of(13, 11), List.of(5, 11),
                    List.of(5, 3), List.of(4, 10), List.of(3, 4))),
            Map.entry(6, List.of(List.of(15, 11), List.of(13, 12), List.of(11, 1),
                    List.of(9, 14), List.of(6, 0), List.of(3, 7), List.of(3, 3),
                    List.of(2, 9))),
            Map.entry(7, List.of(List.of(13, 13), List.of(13, 12), List.of(13, 2),
                    List.of(7, 14), List.of(4, 10), List.of(1, 5))),
            Map.entry(8, List.of(List.of(16, 10), List.of(1, 6), List.of(1, 5))),
            Map.entry(9, List.of(List.of(15, 9), List.of(15, 5), List.of(15, 4),
                    List.of(14, 4), List.of(3, 6), List.of(1, 3))),
            Map.entry(10, List.of(List.of(15, 8), List.of(15, 4), List.of(14, 6),
                    List.of(13, 12), List.of(10, 2), List.of(5, 13))),
            Map.entry(11, List.of(List.of(15, 8), List.of(14, 10), List.of(14, 7),
                    List.of(14, 5), List.of(14, 4), List.of(12, 11), List.of(4, 11),
                    List.of(4, 9))),
            Map.entry(12, List.of(List.of(12, 11), List.of(6, 11), List.of(4, 6))),
            Map.entry(13, List.of(List.of(12, 6), List.of(8, 8), List.of(8, 6), List.of(7, 8), List.of(5, 7))),
            Map.entry(14, List.of(List.of(9, 7))),
            Map.entry(15, List.of())
    );

    // Decompress list, create buds
    static Set<GeodesyBlockPos> buddingAmethysts  = geodeCompressed.entrySet().stream()
            .flatMap(e -> e.getValue().stream()
                    .map(partialCoord -> new GeodesyBlockPos(e.getKey(), partialCoord.get(0), partialCoord.get(1))))
            .collect(Collectors.toSet());
}
