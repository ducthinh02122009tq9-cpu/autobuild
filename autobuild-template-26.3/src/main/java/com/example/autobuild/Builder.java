package com.example.autobuild;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Bộ máy xây tự động (v2). Mọi hành động đi qua đúng cơ chế người chơi thật:
 * đi bộ bằng phím (tìm đường 3D), nhảy bậc, dựng cột leo bằng cách nhảy + đặt khối dưới chân,
 * dỡ cột bằng cách đào xuống, đặt khối trong tầm với 4.4, đổ/múc xô, bấm chuột phải chỉnh repeater,
 * mở rương và shift-click lấy đồ.
 */
public final class Builder {
    public static final Builder I = new Builder();

    private static final int WINDOW = 400;
    private static final int MAX_FINDS = 25;

    static final class Target {
        final BlockPos rel;
        BlockPos pos;
        final BlockState state;
        final Item item;
        final boolean fluid;
        boolean done, gaveUp;
        int fails, attempts;
        long deferUntil;

        Target(BlockPos rel, BlockState state, Item item, boolean fluid) {
            this.rel = rel;
            this.state = state;
            this.item = item;
            this.fluid = fluid;
        }
    }

    static final class Tower {
        int cx, cz, baseY, topY, curTop;
        boolean placed;
        final Set<BlockPos> cells = new HashSet<>();
    }

    private enum St { IDLE, BUILD, NAV, FETCH_OPEN, FETCH_TAKE, TOWER_UP, DESCEND, REFILL }
    private enum Purpose { PERCH, CHEST, TOWER_BASE, TOWER_TOP, REFILL }
    private enum Kind { PLACE, BUCKET, CLICK }

    // cấu hình
    public int delay = 3;
    public boolean towerOn = true;
    private Item scaffold = Items.DIRT;
    private BlockPos origin, chestPos;
    private final Map<Item, BlockPos> refill = new HashMap<>();

    // dữ liệu bản vẽ
    private final List<Target> targets = new ArrayList<>();
    private final Map<String, Integer> skipped = new TreeMap<>();
    private int autoSkipped;
    private String schemName = "";
    private int minX, minZ, maxX, maxZ;

    // trạng thái chạy
    private St st = St.IDLE;
    private int head, doneCount, cooldown, backTicks, phase, noPlace;
    private long tickNo;
    private Placer.Placement cur;
    private Target curT;
    private Kind curKind = Kind.PLACE;
    private Map<Item, Integer> wanted;
    private Set<Item> wantedBefore = new HashSet<>();
    private final Set<Item> outOfStock = new HashSet<>();
    private final Random rnd = new Random();

    // di chuyển
    private List<BlockPos> path;
    private Purpose purpose, resume;
    private Target perchFor;
    private int navStuck, navAge, navRepath;
    private Vec3 navLast;
    private Tower tower;
    private boolean finishing, scaffoldFetched;
    private int scaffoldNeed;
    private BlockPos breaking;
    private Item refillItem;
    private int refillFail, refillLast;

    // ================================================================== cấu hình
    public String load(Path path) throws IOException {
        Litematic lit = Litematic.read(path);
        stop(null);
        targets.clear();
        skipped.clear();
        outOfStock.clear();
        autoSkipped = 0;
        head = 0;
        doneCount = 0;
        schemName = lit.name;
        for (Litematic.Region g : lit.regions) {
            BlockState[] states = new BlockState[g.palette.size()];
            for (int i = 0; i < states.length; i++) states[i] = Rules.toState(g.palette.get(i));
            int n = 0;
            for (int y = 0; y < g.sy; y++)
                for (int z = 0; z < g.sz; z++)
                    for (int x = 0; x < g.sx; x++) {
                        int pi = g.idx[n++];
                        BlockState s = states[pi];
                        String nm = g.palette.get(pi).name();
                        if (nm.equals("minecraft:air") || nm.equals("minecraft:cave_air")
                                || nm.equals("minecraft:void_air")) continue;
                        if (s == null) { skipped.merge(nm + " (không có trong game)", 1, Integer::sum); continue; }
                        BlockPos rel = new BlockPos(g.minX + x, g.minY + y, g.minZ + z);
                        if (s.getBlock() instanceof LiquidBlock) {
                            if (s.getFluidState().isSource()) {
                                Item bucket = s.getBlock() == Blocks.LAVA ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
                                targets.add(new Target(rel, s, bucket, true));
                            } else autoSkipped++; // nước/lava chảy: tự sinh ra từ các nguồn
                            continue;
                        }
                        if (s.getBlock() instanceof BubbleColumnBlock) { autoSkipped++; continue; }
                        Item item = s.getBlock().asItem();
                        if (Rules.unsupported(s)) { autoSkipped++; continue; }
                        if (item == Items.AIR) { skipped.merge(nm, 1, Integer::sum); continue; }
                        targets.add(new Target(rel, s, item, false));
                    }
        }
        targets.sort((a, b) -> {
            int c = Boolean.compare(a.fluid, b.fluid); // chất lỏng đặt sau cùng
            if (c != 0) return c;
            c = Integer.compare(a.rel.getY(), b.rel.getY());
            if (c != 0) return c;
            int sa = Math.floorDiv(a.rel.getZ(), 6), sb = Math.floorDiv(b.rel.getZ(), 6);
            c = Integer.compare(sa, sb);
            if (c != 0) return c;
            c = (sa & 1) == 0 ? Integer.compare(a.rel.getX(), b.rel.getX()) : Integer.compare(b.rel.getX(), a.rel.getX());
            return c != 0 ? c : Integer.compare(a.rel.getZ(), b.rel.getZ());
        });
        long fl = targets.stream().filter(t -> t.fluid).count();
        if (origin != null) setOrigin(origin);
        return "Đã nạp '" + schemName + "': " + targets.size() + " khối cần đặt (trong đó " + fl
                + " nguồn nước/lava), " + autoSkipped + " khối chảy/tự sinh được bỏ qua.";
    }

    public void setOrigin(BlockPos o) {
        origin = o;
        minX = minZ = Integer.MAX_VALUE;
        maxX = maxZ = Integer.MIN_VALUE;
        for (Target t : targets) {
            t.pos = o.offset(t.rel);
            minX = Math.min(minX, t.pos.getX());
            maxX = Math.max(maxX, t.pos.getX());
            minZ = Math.min(minZ, t.pos.getZ());
            maxZ = Math.max(maxZ, t.pos.getZ());
        }
    }

    public void setChest(BlockPos p) { chestPos = p; }
    public void setRefill(Item filledBucket, BlockPos p) { refill.put(filledBucket, p); }
    public boolean setScaffold(String id) {
        Item it = McApi.itemByName(id);
        if (it == null) return false;
        scaffold = it;
        return true;
    }
    public boolean isRunning() { return st != St.IDLE; }
    public boolean isLoaded() { return !targets.isEmpty(); }
    public boolean hasOrigin() { return origin != null; }

    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append(isRunning() ? "ĐANG XÂY (" + st + ")" : "Đang dừng").append(" | ").append(schemName)
          .append(" | xong ").append(doneCount).append("/").append(targets.size());
        long gave = targets.stream().filter(t -> t.gaveUp).count();
        if (gave > 0) sb.append(" | bỏ cuộc ").append(gave);
        sb.append("\nCột leo: ").append(towerOn ? "bật" : "tắt").append(" (vật liệu: ").append(scaffold).append(")");
        if (!skipped.isEmpty()) sb.append("\nBỏ qua (cần làm thủ công): ").append(skipped);
        if (!outOfStock.isEmpty()) sb.append("\nHết trong rương: ").append(outOfStock);
        return sb.toString();
    }

    public void start() {
        for (Target t : targets) { t.done = false; t.gaveUp = false; t.fails = 0; t.attempts = 0; t.deferUntil = 0; }
        head = 0;
        doneCount = 0;
        outOfStock.clear();
        cur = null;
        cooldown = 0;
        noPlace = 0;
        tower = null;
        finishing = false;
        scaffoldFetched = false;
        resume = null;
        st = St.BUILD;
    }

    public void stop(String why) {
        if (st != St.IDLE) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
            if (breaking != null && mc.gameMode != null) McApi.stopBreaking(mc);
            McApi.releaseKeys(mc);
        }
        breaking = null;
        st = St.IDLE;
        cur = null;
        path = null;
        if (why != null) McApi.msg(why);
    }

    // ================================================================== vòng lặp chính
    public void tick() {
        if (st == St.IDLE) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        if (p == null || lvl == null || mc.gameMode == null) { st = St.IDLE; return; }
        if (!p.isAlive()) { stop("Nhân vật đã chết, dừng xây."); return; }
        tickNo++;
        McApi.releaseKeys(mc);
        if (mc.screen != null && st != St.FETCH_OPEN && st != St.FETCH_TAKE) return; // đang mở GUI: tạm dừng
        if (backTicks > 0) { backTicks--; mc.options.keyDown.setDown(true); return; }
        switch (st) {
            case BUILD -> tickBuild(mc, p, lvl);
            case NAV -> tickNav(mc, p, lvl);
            case FETCH_OPEN -> tickFetchOpen(mc, p);
            case FETCH_TAKE -> tickFetchTake(mc, p);
            case TOWER_UP -> tickTower(mc, p, lvl);
            case DESCEND -> tickDescend(mc, p, lvl);
            case REFILL -> tickRefill(mc, p, lvl);
            default -> { }
        }
    }

    private void markDone(Target t) {
        if (!t.done) { t.done = true; doneCount++; }
    }

    private static boolean isFilledBucket(Item it) {
        return it == Items.WATER_BUCKET || it == Items.LAVA_BUCKET;
    }

    private boolean isDone(BlockState w, Target t) {
        if (t.fluid) return w.getBlock() == t.state.getBlock() && w.getFluidState().isSource();
        return Rules.matches(w, t.state) && Rules.clicksNeeded(w, t.state) == 0;
    }

    // ================================================================== xây
    private void tickBuild(Minecraft mc, LocalPlayer p, ClientLevel lvl) {
        if (cooldown > 0) { cooldown--; return; }
        if (cur != null) { aimAndAct(mc, p, lvl); return; }

        while (head < targets.size() && (targets.get(head).done || targets.get(head).gaveUp)) head++;
        if (head >= targets.size()) { finish(); return; }

        Vec3 eye = p.getEyePosition();
        Set<Item> missing = new LinkedHashSet<>();
        Target pick = null;
        Placer.Placement pl = null;
        Kind kind = Kind.PLACE;
        int scanned = 0, finds = 0, lacking = 0;
        for (int i = head; i < targets.size() && scanned < WINDOW; i++) {
            Target t = targets.get(i);
            if (t.done || t.gaveUp) continue;
            scanned++;
            BlockState w = lvl.getBlockState(t.pos);
            if (isDone(w, t)) { markDone(t); continue; }
            if (t.deferUntil > tickNo) continue;
            if (eye.distanceToSqr(Vec3.atCenterOf(t.pos)) > 36.0) continue;

            // repeater/comparator đã đặt nhưng cần bấm chỉnh
            if (!t.fluid && Rules.matches(w, t.state)) {
                Vec3 hit = new Vec3(t.pos.getX() + 0.5, t.pos.getY() + 0.12, t.pos.getZ() + 0.5);
                if (eye.distanceTo(hit) <= 4.3) {
                    float[] look = Placer.lookAt(eye, hit);
                    pick = t;
                    pl = new Placer.Placement(t.pos, w, Direction.UP, hit, look[0], look[1]);
                    kind = Kind.CLICK;
                    break;
                }
                continue;
            }
            if (McApi.count(p, t.item) == 0) {
                if (outOfStock.contains(t.item)) lacking++; else missing.add(t.item);
                continue;
            }
            if (!t.fluid && p.getBoundingBox().intersects(new AABB(t.pos))) { backTicks = 8; return; }
            if (finds++ >= MAX_FINDS) continue;
            Placer.Placement r = t.fluid ? Placer.findBucket(lvl, p, t.pos)
                                         : Placer.find(lvl, p, t.pos, t.state, t.item);
            if (r != null) { pick = t; pl = r; kind = t.fluid ? Kind.BUCKET : Kind.PLACE; break; }
        }
        if (pick != null) { cur = pl; curT = pick; curKind = kind; noPlace = 0; return; }

        if (!missing.isEmpty()) {
            for (Item f : missing) {
                if (isFilledBucket(f) && refill.get(f) != null && McApi.count(p, Items.BUCKET) > 0) {
                    refillItem = f;
                    goTo(mc, p, lvl, Purpose.REFILL);
                    return;
                }
            }
            startFetch(mc, p, lvl);
            return;
        }
        if (lacking > 0) {
            stop("Hết vật liệu trong rương: " + outOfStock + ". Bổ sung rồi gõ /ab start để tiếp tục.");
            return;
        }

        // không còn gì đặt được từ chỗ đang đứng: tìm chỗ đứng mới
        Target g = null;
        boolean waiting = false;
        for (int i = head; i < targets.size(); i++) {
            Target t = targets.get(i);
            if (t.done || t.gaveUp) continue;
            if (t.deferUntil > tickNo) { waiting = true; continue; }
            g = t;
            break;
        }
        if (g == null) {
            if (!waiting) finish();
            return;
        }
        Vec3 c = Vec3.atCenterOf(g.pos);
        if (eye.distanceTo(c) <= 5.0) {
            // đã trong tầm với mà vẫn chưa đặt được (thiếu khối tựa, bị chắn...)
            if (++noPlace > 30) {
                noPlace = 0;
                g.deferUntil = tickNo + 600;
                if (++g.fails >= 3) g.gaveUp = true;
            }
        } else {
            noPlace = 0;
            perchFor = g;
            goTo(mc, p, lvl, Purpose.PERCH);
        }
    }

    private void aimAndAct(Minecraft mc, LocalPlayer p, ClientLevel lvl) {
        BlockState w = lvl.getBlockState(curT.pos);
        if (isDone(w, curT)) { markDone(curT); cur = null; return; }
        if (curKind != Kind.CLICK && !lvl.getBlockState(cur.support()).equals(cur.supportState())) { cur = null; return; }
        float dy = Mth.wrapDegrees(cur.yaw() - p.getYRot());
        float dp = cur.pitch() - p.getXRot();
        if (Math.abs(dy) > 0.01f || Math.abs(dp) > 0.01f) {
            McApi.setRot(p, p.getYRot() + Mth.clamp(dy, -45f, 45f), p.getXRot() + Mth.clamp(dp, -45f, 45f));
            return; // gói xoay đầu được gửi tick sau, rồi mới hành động
        }
        InteractionResult r;
        int limit;
        switch (curKind) {
            case BUCKET -> {
                if (!McApi.hold(mc, p, curT.item)) return;
                r = McApi.useItem(mc, p);
                limit = 6;
            }
            case CLICK -> {
                r = McApi.use(mc, p, new BlockHitResult(cur.hit(), Direction.UP, cur.support(), false));
                limit = 10;
            }
            default -> {
                if (!McApi.hold(mc, p, curT.item)) return;
                r = McApi.use(mc, p, new BlockHitResult(cur.hit(), cur.face(), cur.support(), false));
                limit = 4;
            }
        }
        McApi.swing(p);
        curT.attempts++;
        if ((!r.consumesAction() && ++curT.fails >= 3) || curT.attempts >= limit) curT.gaveUp = true;
        cooldown = delay + (rnd.nextInt(3) == 0 ? 1 : 0);
        cur = null;
    }

    // ================================================================== đi lại
    private Predicate<BlockPos> goalFor(Purpose pu, ClientLevel lvl) {
        switch (pu) {
            case PERCH: {
                if (perchFor == null) return null;
                Vec3 c = Vec3.atCenterOf(perchFor.pos);
                return s -> new Vec3(s.getX() + 0.5, s.getY() + 1.62, s.getZ() + 0.5).distanceTo(c) <= 4.0;
            }
            case CHEST: {
                if (chestPos == null) return null;
                Vec3 c = Vec3.atCenterOf(chestPos);
                return s -> new Vec3(s.getX() + 0.5, s.getY() + 1.62, s.getZ() + 0.5).distanceTo(c) <= 3.6;
            }
            case REFILL: {
                BlockPos src = refillItem == null ? null : refill.get(refillItem);
                if (src == null) return null;
                Vec3 c = Vec3.atCenterOf(src);
                return s -> new Vec3(s.getX() + 0.5, s.getY() + 1.62, s.getZ() + 0.5).distanceTo(c) <= 3.4;
            }
            case TOWER_BASE: {
                if (tower == null) return null;
                BlockPos b = new BlockPos(tower.cx, tower.baseY, tower.cz);
                return s -> s.equals(b);
            }
            case TOWER_TOP: {
                if (tower == null) return null;
                BlockPos b = new BlockPos(tower.cx, tower.curTop, tower.cz);
                return s -> s.equals(b);
            }
            default:
                return null;
        }
    }

    private void goTo(Minecraft mc, LocalPlayer p, ClientLevel lvl, Purpose pu) {
        Predicate<BlockPos> goal = goalFor(pu, lvl);
        if (goal == null) { stop("Thiếu thông tin đích đến (" + pu + ")."); return; }
        List<BlockPos> found = Pathfinder.find(lvl, p.blockPosition(), goal, 60000);
        if (found == null) { unreachable(mc, p, lvl, pu); return; }
        path = found;
        purpose = pu;
        navStuck = 0;
        navAge = 0;
        navRepath = 0;
        st = St.NAV;
    }

    private void unreachable(Minecraft mc, LocalPlayer p, ClientLevel lvl, Purpose pu) {
        switch (pu) {
            case PERCH -> {
                if (perchFor != null && ++perchFor.fails >= 4) {
                    perchFor.gaveUp = true;
                    st = St.BUILD;
                    return;
                }
                if (tower != null) { resume = Purpose.PERCH; goTo(mc, p, lvl, Purpose.TOWER_TOP); return; }
                if (!planTower(mc, p, lvl, perchFor)) {
                    if (perchFor != null) perchFor.deferUntil = tickNo + 600;
                    st = St.BUILD;
                }
            }
            case CHEST, REFILL -> {
                if (tower != null) { resume = pu; goTo(mc, p, lvl, Purpose.TOWER_TOP); }
                else stop("Không có đường đi tới " + (pu == Purpose.CHEST ? "rương" : "điểm múc chất lỏng") + ".");
            }
            default -> stop("Không tìm được đường đi (" + pu + ").");
        }
    }

    private void tickNav(Minecraft mc, LocalPlayer p, ClientLevel lvl) {
        if (path == null || path.isEmpty()) { st = St.BUILD; return; }
        if (++navAge > 1500) { navFailed(mc, p, lvl); return; }
        Predicate<BlockPos> goal = goalFor(purpose, lvl);
        BlockPos cell = p.blockPosition();
        if (goal != null && p.onGround() && goal.test(cell) && Pathfinder.standable(lvl, cell)) { arrived(); return; }

        int idx = -1;
        for (int i = path.size() - 1; i >= 0; i--) if (path.get(i).equals(cell)) { idx = i; break; }
        if (idx < 0) { // lệch khỏi đường đã tính: tính lại
            if (++navRepath > 6 || goal == null) { navFailed(mc, p, lvl); return; }
            List<BlockPos> np = Pathfinder.find(lvl, cell, goal, 60000);
            if (np == null) { navFailed(mc, p, lvl); return; }
            path = np;
            return;
        }
        BlockPos next = path.get(Math.min(idx + 1, path.size() - 1));
        steer(mc, p, next.getX() + 0.5, next.getZ() + 0.5, true);
        if (next.getY() > cell.getY() && p.onGround()) mc.options.keyJump.setDown(true);

        Vec3 now = p.position();
        navStuck = (navLast != null && now.distanceToSqr(navLast) < 0.0004) ? navStuck + 1 : 0;
        navLast = now;
        if (navStuck > 60) {
            navStuck = 0;
            if (++navRepath > 6 || goal == null) { navFailed(mc, p, lvl); return; }
            List<BlockPos> np = Pathfinder.find(lvl, cell, goal, 60000);
            if (np == null) navFailed(mc, p, lvl); else path = np;
        }
    }

    private void navFailed(Minecraft mc, LocalPlayer p, ClientLevel lvl) {
        path = null;
        st = St.BUILD;
        if (purpose == Purpose.PERCH) {
            if (perchFor != null) {
                perchFor.deferUntil = tickNo + 400;
                if (++perchFor.fails >= 4) perchFor.gaveUp = true;
            }
        } else {
            stop("Nhân vật bị kẹt hoặc mất đường (" + purpose + "). Hãy di chuyển ra chỗ thoáng rồi gõ /ab start.");
        }
    }

    private void arrived() {
        path = null;
        phase = 0;
        switch (purpose) {
            case PERCH -> st = St.BUILD;
            case CHEST -> st = St.FETCH_OPEN;
            case TOWER_BASE -> st = St.TOWER_UP;
            case TOWER_TOP -> { st = St.DESCEND; breaking = null; }
            case REFILL -> { st = St.REFILL; refillFail = 0; refillLast = McApi.count(Minecraft.getInstance().player, refillItem); }
        }
    }

    private void steer(Minecraft mc, LocalPlayer p, double gx, double gz, boolean sprintOk) {
        double dx = gx - p.getX(), dz = gz - p.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.05) return;
        float want = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float diff = Mth.wrapDegrees(want - p.getYRot());
        McApi.setRot(p, p.getYRot() + Mth.clamp(diff, -35f, 35f), p.getXRot() + Mth.clamp(8f - p.getXRot(), -10f, 10f));
        if (Math.abs(diff) < 40f) {
            mc.options.keyUp.setDown(true);
            if (sprintOk && dist > 6) mc.options.keySprint.setDown(true);
        }
        if (p.horizontalCollision && p.onGround()) mc.options.keyJump.setDown(true);
    }

    // ================================================================== cột leo (dựng bằng cách nhảy + đặt khối)
    private boolean planTower(Minecraft mc, LocalPlayer p, ClientLevel lvl, Target g) {
        if (!towerOn || g == null || minX == Integer.MAX_VALUE) return false;
        int h = g.pos.getY();
        BlockPos best = null;
        int bestBase = 0;
        double bestD = Double.MAX_VALUE;
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                boolean outX = x < minX || x > maxX, outZ = z < minZ || z > maxZ;
                if (outX == outZ) continue; // chỉ lấy ô sát cạnh (không lấy góc, không lấy bên trong)
                BlockPos edge = new BlockPos(Mth.clamp(x, minX, maxX), h, Mth.clamp(z, minZ, maxZ));
                if (!Pathfinder.standable(lvl, edge)) continue; // rìa công trình phải có mặt đứng ở độ cao h
                int y = h;
                while (y > lvl.getMinY() && Pathfinder.passable(lvl, new BlockPos(x, y - 1, z))) y--;
                if (h - y < 3 || h - y > 40) continue;
                double d = Math.hypot(x - g.pos.getX(), z - g.pos.getZ());
                if (d < bestD) { bestD = d; best = new BlockPos(x, h, z); bestBase = y; }
            }
        }
        if (best == null) return false;

        int need = (h - bestBase) + 2;
        if (McApi.count(p, scaffold) < need) {
            if (scaffoldFetched || outOfStock.contains(scaffold)) {
                stop("Không đủ " + scaffold + " để dựng cột leo (cần " + need + "). Bỏ vào rương hoặc túi đồ rồi /ab start.");
                return true;
            }
            scaffoldFetched = true;
            scaffoldNeed = need;
            startFetch(mc, p, lvl);
            return true;
        }
        scaffoldFetched = false;
        tower = new Tower();
        tower.cx = best.getX();
        tower.cz = best.getZ();
        tower.baseY = bestBase;
        tower.curTop = bestBase;
        tower.topY = h;
        McApi.msg("Dựng cột leo cao " + (h - bestBase) + " khối để lên độ cao y=" + h + ".");
        goTo(mc, p, lvl, Purpose.TOWER_BASE);
        return true;
    }

    private void lookDown(LocalPlayer p) {
        McApi.setRot(p, p.getYRot(), p.getXRot() + Mth.clamp(90f - p.getXRot(), -30f, 30f));
    }

    private void tickTower(Minecraft mc, LocalPlayer p, ClientLevel lvl) {
        Tower tw = tower;
        if (tw == null) { st = St.BUILD; return; }
        if (++phase > 1500) { stop("Dựng cột quá lâu, dừng lại."); return; }
        lookDown(p);
        double dx = tw.cx + 0.5 - p.getX(), dz = tw.cz + 0.5 - p.getZ();
        if (Math.hypot(dx, dz) > 0.25 && p.onGround()) { // căn giữa cột
            steer(mc, p, tw.cx + 0.5, tw.cz + 0.5, false);
            return;
        }
        if (p.onGround() && Math.abs(p.getY() - tw.curTop) < 0.05) {
            if (tw.curTop >= tw.topY) { // đã lên tới độ cao cần thiết
                goTo(mc, p, lvl, Purpose.PERCH);
                return;
            }
            mc.options.keyJump.setDown(true);
            tw.placed = false;
        } else if (!tw.placed && p.getY() > tw.curTop + 1.02) {
            if (!McApi.hold(mc, p, scaffold)) return;
            BlockPos sup = new BlockPos(tw.cx, tw.curTop - 1, tw.cz);
            Vec3 hp = new Vec3(tw.cx + 0.5, tw.curTop, tw.cz + 0.5);
            McApi.use(mc, p, new BlockHitResult(hp, Direction.UP, sup, false));
            McApi.swing(p);
            tw.cells.add(new BlockPos(tw.cx, tw.curTop, tw.cz));
            tw.curTop++;
            tw.placed = true;
        }
    }

    /** Dỡ cột bằng cách đào khối dưới chân từng cái một và rơi xuống. */
    private void tickDescend(Minecraft mc, LocalPlayer p, ClientLevel lvl) {
        Tower tw = tower;
        if (tw == null) { afterDescent(mc, p, lvl); return; }
        if (++phase > 3000) { stop("Dỡ cột quá lâu, dừng lại."); return; }
        lookDown(p);
        if (!p.onGround()) return;
        int fy = Mth.floor(p.getY() + 0.001);
        BlockPos below = new BlockPos(tw.cx, fy - 1, tw.cz);
        if (Math.hypot(tw.cx + 0.5 - p.getX(), tw.cz + 0.5 - p.getZ()) > 0.9 || !tw.cells.contains(below)) {
            if (breaking != null) { McApi.stopBreaking(mc); breaking = null; }
            tower = null; // xong (hoặc đã rời khỏi cột)
            afterDescent(mc, p, lvl);
            return;
        }
        BlockState s = lvl.getBlockState(below);
        if (s.isAir()) { tw.cells.remove(below); breaking = null; return; }
        McApi.holdBestTool(p, s);
        McApi.breakStep(mc, below, !below.equals(breaking));
        breaking = below;
        McApi.swing(p);
    }

    private void afterDescent(Minecraft mc, LocalPlayer p, ClientLevel lvl) {
        tower = null;
        if (finishing) { finishing = false; finish(); return; }
        Purpose r = resume;
        resume = null;
        if (r != null) goTo(mc, p, lvl, r); else st = St.BUILD;
    }

    // ================================================================== múc chất lỏng bằng xô
    private void tickRefill(Minecraft mc, LocalPlayer p, ClientLevel lvl) {
        BlockPos src = refillItem == null ? null : refill.get(refillItem);
        if (src == null) { stop("Chưa đặt điểm múc cho chất lỏng này (/ab water hoặc /ab lava)."); return; }
        phase++;
        Vec3 eye = p.getEyePosition();
        float[] look = Placer.lookAt(eye, Vec3.atCenterOf(src));
        float dy = Mth.wrapDegrees(look[0] - p.getYRot());
        float dp = look[1] - p.getXRot();
        if (Math.abs(dy) > 0.5f || Math.abs(dp) > 0.5f) {
            McApi.setRot(p, p.getYRot() + Mth.clamp(dy, -45f, 45f), p.getXRot() + Mth.clamp(dp, -45f, 45f));
            return;
        }
        int filled = McApi.count(p, refillItem);
        if (filled > refillLast) refillFail = 0;
        refillLast = filled;
        if (McApi.count(p, Items.BUCKET) == 0 || filled >= 16) { st = St.BUILD; cooldown = 4; return; }
        if (!McApi.hold(mc, p, Items.BUCKET)) return;
        if (phase % 6 == 0) {
            if (++refillFail > 8) { stop("Không múc được chất lỏng ở điểm đã chọn (hết nguồn?)."); return; }
            McApi.useItem(mc, p);
            McApi.swing(p);
        }
    }

    // ================================================================== lấy đồ từ rương
    private void startFetch(Minecraft mc, LocalPlayer p, ClientLevel lvl) {
        if (chestPos == null) {
            stop("Thiếu vật liệu nhưng chưa chọn rương. Nhìn vào rương rồi gõ /ab chest, sau đó /ab start.");
            return;
        }
        Map<Item, Integer> need = new LinkedHashMap<>();
        int seen = 0;
        for (int i = head; i < targets.size() && seen < 1500; i++) {
            Target t = targets.get(i);
            if (t.done || t.gaveUp) continue;
            seen++;
            need.merge(t.item, 1, Integer::sum);
        }
        wanted = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : need.entrySet()) {
            Item it = e.getKey();
            if (outOfStock.contains(it)) continue;
            if (isFilledBucket(it)) {
                if (refill.get(it) != null) {
                    int w = 16 - McApi.count(p, Items.BUCKET);
                    if (w > 0) wanted.put(Items.BUCKET, w);
                } else {
                    int w = Math.min(e.getValue(), 16) - McApi.count(p, it);
                    if (w > 0) wanted.put(it, w);
                }
                continue;
            }
            int cap = Math.min(e.getValue(), it.getDefaultMaxStackSize() * 3);
            int want = cap - McApi.count(p, it);
            if (want > 0) wanted.put(it, want);
            if (wanted.size() >= 18) break;
        }
        if (scaffoldNeed > 0) {
            int w = scaffoldNeed - McApi.count(p, scaffold);
            if (w > 0) wanted.put(scaffold, w);
            scaffoldNeed = 0;
        }
        if (wanted.isEmpty()) { stop("Không có gì để lấy từ rương nhưng vẫn thiếu vật liệu. Kiểm tra lại /ab status."); return; }
        wantedBefore = new HashSet<>(wanted.keySet());
        McApi.msg("Đi lấy vật liệu từ rương...");
        goTo(mc, p, lvl, Purpose.CHEST);
    }

    private void tickFetchOpen(Minecraft mc, LocalPlayer p) {
        if (p.containerMenu != p.inventoryMenu) { st = St.FETCH_TAKE; phase = 0; return; }
        if (++phase > 80) { stop("Không mở được rương (bị chắn hoặc sai vị trí)."); return; }
        Vec3 eye = p.getEyePosition();
        Vec3 c = Vec3.atCenterOf(chestPos);
        float[] look = Placer.lookAt(eye, c);
        float dy = Mth.wrapDegrees(look[0] - p.getYRot());
        float dp = look[1] - p.getXRot();
        McApi.setRot(p, p.getYRot() + Mth.clamp(dy, -45f, 45f), p.getXRot() + Mth.clamp(dp, -45f, 45f));
        if (Math.abs(dy) < 2f && Math.abs(dp) < 2f && phase % 10 == 0) {
            Direction f = Direction.getApproximateNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z);
            Vec3 hp = c.add(f.getStepX() * 0.5, f.getStepY() * 0.5, f.getStepZ() * 0.5);
            McApi.use(mc, p, new BlockHitResult(hp, f, chestPos, false));
            McApi.swing(p);
        }
    }

    private void tickFetchTake(Minecraft mc, LocalPlayer p) {
        AbstractContainerMenu menu = p.containerMenu;
        if (menu == p.inventoryMenu) { st = St.BUILD; cooldown = 5; return; }
        if (phase++ % 2 != 0) return;
        for (Slot s : menu.slots) {
            if (s.container == p.getInventory()) continue;
            ItemStack stack = s.getItem();
            if (stack.isEmpty()) continue;
            Integer w = wanted.get(stack.getItem());
            if (w == null || w <= 0) continue;
            wanted.put(stack.getItem(), w - stack.getCount());
            McApi.click(mc, p, menu.containerId, s.index, 0, ClickType.QUICK_MOVE);
            return;
        }
        p.closeContainer();
        for (Item it : wantedBefore) if (McApi.count(p, it) == 0) outOfStock.add(it);
        st = St.BUILD;
        cooldown = 6;
        McApi.msg("Đã lấy vật liệu, tiếp tục xây.");
    }

    private void finish() {
        Minecraft mc = Minecraft.getInstance();
        if (tower != null && mc.player != null && mc.level != null) { // dỡ cột trước khi kết thúc
            finishing = true;
            resume = null;
            goTo(mc, mc.player, mc.level, Purpose.TOWER_TOP);
            return;
        }
        long gave = targets.stream().filter(t -> t.gaveUp).count();
        stop("HOÀN TẤT: " + doneCount + "/" + targets.size() + " khối."
                + (gave > 0 ? " " + gave + " khối không đặt được (không có đường, thiếu khối tựa hoặc bị chắn)." : "")
                + (skipped.isEmpty() ? "" : " Bỏ qua: " + skipped));
    }
}
