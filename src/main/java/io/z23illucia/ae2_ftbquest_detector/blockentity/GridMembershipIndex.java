package io.z23illucia.ae2_ftbquest_detector.blockentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GridMembershipIndex<M, G> {
    private final Map<M, G> gridByMember = new IdentityHashMap<>();
    private final Map<G, Set<M>> membersByGrid = new IdentityHashMap<>();

    synchronized G update(M member, G grid) {
        G previousGrid = gridByMember.get(member);
        if (previousGrid == grid) {
            return previousGrid;
        }

        removeFromGrid(member, previousGrid);
        if (grid == null) {
            gridByMember.remove(member);
            return previousGrid;
        }

        gridByMember.put(member, grid);
        membersByGrid.computeIfAbsent(grid, ignored -> newIdentitySet()).add(member);
        return previousGrid;
    }

    synchronized G remove(M member) {
        G previousGrid = gridByMember.remove(member);
        removeFromGrid(member, previousGrid);
        return previousGrid;
    }

    synchronized List<M> copyForGrid(G grid) {
        if (grid == null) {
            return List.of();
        }
        Set<M> members = membersByGrid.get(grid);
        return members == null || members.isEmpty() ? List.of() : new ArrayList<>(members);
    }

    private void removeFromGrid(M member, G grid) {
        if (grid == null) {
            return;
        }
        Set<M> members = membersByGrid.get(grid);
        if (members != null) {
            members.remove(member);
            if (members.isEmpty()) {
                membersByGrid.remove(grid);
            }
        }
    }

    private static <T> Set<T> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
