package com.example.autobuild;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tìm đường 3D trên thế giới THẬT (đang tồn tại), giống cách người chơi đi lại:
 * đi ngang, nhảy lên bậc cao 1 khối, nhảy xuống tối đa 3 khối (không mất máu).
 * Không đặt/phá khối nào: việc dựng cột leo do Builder đảm nhận.
 */
public final class Pathfinder {
    private Pathfinder() {}

    private record Node(BlockPos pos, int cost) {}

    public static boolean passable(ClientLevel l, BlockPos p) {
        BlockState s = l.getBlockState(p);
        return s.getCollisionShape(l, p).isEmpty() && s.getFluidState().isEmpty();
    }

    public static boolean solid(ClientLevel l, BlockPos p) {
        return !l.getBlockState(p).getCollisionShape(l, p).isEmpty();
    }

    /** Ô đứng được: chân và đầu thông thoáng, bên dưới là khối đặc. */
    public static boolean standable(ClientLevel l, BlockPos p) {
        return passable(l, p) && passable(l, p.above()) && solid(l, p.below());
    }

    /** Trả về danh sách ô từ vị trí bắt đầu tới ô đầu tiên thỏa goal, hoặc null nếu không có đường. */
    public static List<BlockPos> find(ClientLevel l, BlockPos start, Predicate<BlockPos> goal, int maxNodes) {
        PriorityQueue<Node> pq = new PriorityQueue<>(Comparator.comparingInt(Node::cost));
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Map<BlockPos, Integer> best = new HashMap<>();
        pq.add(new Node(start, 0));
        best.put(start, 0);
        parent.put(start, null);
        int visited = 0;
        while (!pq.isEmpty() && visited++ < maxNodes) {
            Node n = pq.poll();
            BlockPos cur = n.pos();
            if (n.cost() > best.getOrDefault(cur, Integer.MAX_VALUE)) continue;
            if (standable(l, cur) && goal.test(cur)) return rebuild(parent, cur);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos nx = cur.relative(d);
                if (!passable(l, nx) || !passable(l, nx.above())) {
                    // bị chắn: thử nhảy lên bậc cao 1 khối
                    BlockPos up = nx.above();
                    if (passable(l, cur.above(2)) && standable(l, up)) push(pq, best, parent, up, cur, n.cost() + 3);
                    continue;
                }
                if (standable(l, nx)) {
                    push(pq, best, parent, nx, cur, n.cost() + 1);
                } else {
                    for (int k = 1; k <= 3; k++) { // bước ra mép và rơi tối đa 3 khối
                        BlockPos m = nx.below(k);
                        if (standable(l, m)) { push(pq, best, parent, m, cur, n.cost() + 1 + k); break; }
                        if (!passable(l, m)) break;
                    }
                }
            }
        }
        return null;
    }

    private static void push(PriorityQueue<Node> pq, Map<BlockPos, Integer> best, Map<BlockPos, BlockPos> parent,
                             BlockPos to, BlockPos from, int cost) {
        if (cost < best.getOrDefault(to, Integer.MAX_VALUE)) {
            best.put(to, cost);
            parent.put(to, from);
            pq.add(new Node(to, cost));
        }
    }

    private static List<BlockPos> rebuild(Map<BlockPos, BlockPos> parent, BlockPos end) {
        List<BlockPos> path = new ArrayList<>();
        for (BlockPos c = end; c != null; c = parent.get(c)) path.add(c);
        Collections.reverse(path);
        return path;
    }
}
