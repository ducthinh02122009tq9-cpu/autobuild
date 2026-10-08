package com.example.autobuild;

import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.MovingPistonBlock;
import net.minecraft.world.level.block.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;

/** Quy tắc chuyển đổi và so sánh trạng thái khối. */
public final class Rules {
    private Rules() {}

    /** Các thuộc tính do game tự tính / thay đổi theo thời gian: không dùng để so sánh. */
    private static final Set<String> IGNORE = Set.of(
            "power", "powered", "lit", "triggered", "north", "east", "south", "west", "up", "down",
            "attached", "disarmed", "enabled", "open", "waterlogged", "distance", "persistent",
            "extended", "shape", "locked", "delay", "mode", "note", "instrument", "crafting",
            "hinge", "unstable", "bottom");

    public static boolean matches(BlockState a, BlockState b) {
        if (a.getBlock() != b.getBlock()) return false;
        boolean chest = a.getBlock() instanceof ChestBlock;
        for (Property<?> p : a.getProperties()) {
            String n = p.getName();
            if (IGNORE.contains(n) || (chest && n.equals("type"))) continue;
            if (!a.getValue(p).equals(b.getValue(p))) return false;
        }
        return true;
    }

    /** Khối không cần / không thể đặt trực tiếp (đầu piston, nửa trên của cửa...). */
    public static boolean unsupported(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof PistonHeadBlock || b instanceof MovingPistonBlock) return true;
        return s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
    }

    /** Số lần phải bấm chuột phải để chỉnh repeater (độ trễ) / comparator (chế độ) cho đúng bản vẽ. */
    public static int clicksNeeded(BlockState world, BlockState target) {
        if (world.getBlock() != target.getBlock()) return 0;
        if (target.hasProperty(BlockStateProperties.DELAY)) {
            int w = world.getValue(BlockStateProperties.DELAY);
            int t = target.getValue(BlockStateProperties.DELAY);
            return ((t - w) % 4 + 4) % 4; // mỗi lần bấm: 1 -> 2 -> 3 -> 4 -> 1
        }
        if (target.hasProperty(BlockStateProperties.MODE_COMPARATOR)) {
            return world.getValue(BlockStateProperties.MODE_COMPARATOR)
                    == target.getValue(BlockStateProperties.MODE_COMPARATOR) ? 0 : 1;
        }
        return 0;
    }

    public static BlockState toState(Litematic.Entry e) {
        Block b = McApi.blockByName(e.name());
        if (b == null) return null;
        BlockState s = b.defaultBlockState();
        for (Map.Entry<String, String> en : e.props().entrySet()) {
            Property<?> p = b.getStateDefinition().getProperty(en.getKey());
            if (p != null) s = withProp(s, p, en.getValue());
        }
        return s;
    }

    private static <T extends Comparable<T>> BlockState withProp(BlockState s, Property<T> p, String v) {
        return p.getValue(v).map(x -> s.setValue(p, x)).orElse(s);
    }
}
