package com.example.autobuild;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Tìm cách đặt một khối "đúng như người chơi thật":
 * thử từng khối tựa (support) + mặt bấm + hướng nhìn, rồi để CHÍNH code của game
 * (Block.getStateForPlacement) tính ra trạng thái. Chỉ chọn cách cho ra đúng trạng thái trong bản vẽ.
 * Ngoài ra tia nhìn từ mắt tới điểm bấm phải không bị khối nào chắn, và nằm trong tầm với.
 */
public final class Placer {
    private Placer() {}

    public record Placement(BlockPos support, BlockState supportState, Direction face,
                            Vec3 hit, float yaw, float pitch) {}

    public static final double REACH = 4.4;
    private static final Direction[] ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};
    private static final float[] YAWS = {0f, 90f, 180f, -90f};
    private static final float[] PITCHES = {0f, -90f, 90f};

    public static Placement find(ClientLevel lvl, LocalPlayer p, BlockPos pos, BlockState target, Item item) {
        Vec3 eye = p.getEyePosition();
        ItemStack stack = new ItemStack(item);
        float oy = p.getYRot(), ox = p.getXRot();
        try {
            for (Direction d : ORDER) {
                BlockPos sup = pos.relative(d);
                BlockState ss = lvl.getBlockState(sup);
                if (!usableSupport(ss)) continue;
                Direction face = d.getOpposite();
                double[] ys = face.getAxis().isHorizontal() ? new double[] {0.5, 0.25, 0.75} : new double[] {0.5};
                for (double v : ys) {
                    Vec3 aim = facePoint(sup, face, v);
                    if (eye.distanceTo(aim) > REACH) continue;
                    Vec3 inside = aim.subtract(face.getStepX() * 0.02, face.getStepY() * 0.02, face.getStepZ() * 0.02);
                    BlockHitResult ray = lvl.clip(new ClipContext(eye, inside,
                            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
                    if (ray.getType() != HitResult.Type.BLOCK || !ray.getBlockPos().equals(sup)
                            || ray.getDirection() != face) continue;
                    Vec3 hit = ray.getLocation();
                    for (float[] rot : rotations(lookAt(eye, hit))) {
                        p.setYRot(rot[0]);
                        p.setXRot(rot[1]);
                        BlockHitResult bhr = new BlockHitResult(hit, face, sup, false);
                        BlockPlaceContext ctx = new BlockPlaceContext(p, InteractionHand.MAIN_HAND, stack, bhr);
                        if (!ctx.canPlace()) continue;
                        BlockState s = target.getBlock().getStateForPlacement(ctx);
                        if (s != null && Rules.matches(s, target) && s.canSurvive(lvl, pos)) {
                            return new Placement(sup, ss, face, hit, rot[0], rot[1]);
                        }
                    }
                }
            }
            return null;
        } finally {
            p.setYRot(oy);
            p.setXRot(ox);
        }
    }

    /** Tìm chỗ đứng/góc nhìn để đổ xô nước/lava vào đúng ô pos (xô đổ vào ô nằm trước mặt khối bị chỉ vào). */
    public static Placement findBucket(ClientLevel lvl, LocalPlayer p, BlockPos pos) {
        BlockState here = lvl.getBlockState(pos);
        if (!(here.isAir() || here.canBeReplaced() || !here.getFluidState().isEmpty())) return null;
        Vec3 eye = p.getEyePosition();
        for (Direction d : ORDER) {
            BlockPos sup = pos.relative(d);
            BlockState ss = lvl.getBlockState(sup);
            if (ss.isAir() || !ss.getFluidState().isEmpty() || ss.canBeReplaced()) continue;
            Direction face = d.getOpposite();
            double[] ys = face.getAxis().isHorizontal() ? new double[] {0.5, 0.25, 0.75} : new double[] {0.5};
            for (double v : ys) {
                Vec3 aim = facePoint(sup, face, v);
                if (eye.distanceTo(aim) > REACH) continue;
                Vec3 inside = aim.subtract(face.getStepX() * 0.02, face.getStepY() * 0.02, face.getStepZ() * 0.02);
                BlockHitResult ray = lvl.clip(new ClipContext(eye, inside,
                        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
                if (ray.getType() != HitResult.Type.BLOCK || !ray.getBlockPos().equals(sup)
                        || ray.getDirection() != face) continue;
                float[] look = lookAt(eye, ray.getLocation());
                return new Placement(sup, ss, face, ray.getLocation(), look[0], look[1]);
            }
        }
        return null;
    }

    /** Khối tựa phải là khối đặc, không phải khối mà bấm vào sẽ mở GUI / bật tắt / xoay. */
    private static boolean usableSupport(BlockState s) {
        if (s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced()) return false;
        var b = s.getBlock();
        return !(b instanceof EntityBlock || b instanceof NoteBlock || b instanceof ButtonBlock
                || b instanceof LeverBlock || b instanceof DiodeBlock || b instanceof DoorBlock
                || b instanceof TrapDoorBlock || b instanceof FenceGateBlock || b instanceof RedStoneWireBlock);
    }

    private static Vec3 facePoint(BlockPos sup, Direction face, double v) {
        Vec3 c = Vec3.atCenterOf(sup);
        double y = face.getAxis().isHorizontal() ? sup.getY() + v : c.y + face.getStepY() * 0.5;
        return new Vec3(c.x + face.getStepX() * 0.5, y, c.z + face.getStepZ() * 0.5);
    }

    private static List<float[]> rotations(float[] look) {
        List<float[]> l = new ArrayList<>();
        l.add(look); // nhìn thẳng vào điểm bấm: tự nhiên nhất
        for (float pit : PITCHES) for (float yaw : YAWS) l.add(new float[] {yaw, pit});
        return l;
    }

    public static float[] lookAt(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        return new float[] {yaw, pitch};
    }
}
