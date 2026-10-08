package com.example.autobuild;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;

/**
 * Lệnh:
 *  /ab list                 liệt kê file trong thư mục .minecraft/schematics
 *  /ab load <tên file>      nạp bản vẽ .litematic
 *  /ab origin               đặt gốc xây = khối cạnh mặt đang nhìn (góc nhỏ nhất của bản vẽ)
 *  /ab chest                chọn rương đang nhìn làm kho vật liệu
 *  /ab speed <1-10>         số tick nghỉ giữa 2 lần đặt (mặc định 3, càng nhỏ càng nhanh)
 *  /ab scaffold <item>      vật liệu dựng cột leo (mặc định minecraft:dirt)
 *  /ab tower on|off         bật/tắt tự dựng cột leo
 *  /ab water | /ab lava     nhìn vào một khối nguồn nước/lava để làm điểm múc xô
 *  /ab start | stop | status
 */
public class AutoBuildMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> Builder.I.tick());

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommands.literal("ab")
                .then(ClientCommands.literal("list").executes(ctx -> { list(); return 1; }))
                .then(ClientCommands.literal("load").then(
                    ClientCommands.argument("file", StringArgumentType.greedyString()).executes(ctx -> {
                        load(StringArgumentType.getString(ctx, "file"));
                        return 1;
                    })))
                .then(ClientCommands.literal("origin").executes(ctx -> { origin(); return 1; }))
                .then(ClientCommands.literal("chest").executes(ctx -> { chest(); return 1; }))
                .then(ClientCommands.literal("speed").then(
                    ClientCommands.argument("n", IntegerArgumentType.integer(1, 10)).executes(ctx -> {
                        Builder.I.delay = IntegerArgumentType.getInteger(ctx, "n");
                        McApi.msg("Nghỉ " + Builder.I.delay + " tick giữa mỗi lần đặt khối.");
                        return 1;
                    })))
                .then(ClientCommands.literal("scaffold").then(
                    ClientCommands.argument("item", StringArgumentType.greedyString()).executes(ctx -> {
                        String id = StringArgumentType.getString(ctx, "item");
                        if (!id.contains(":")) id = "minecraft:" + id;
                        McApi.msg(Builder.I.setScaffold(id) ? "Vật liệu cột leo: " + id : "Không tìm thấy vật phẩm " + id);
                        return 1;
                    })))
                .then(ClientCommands.literal("tower")
                    .then(ClientCommands.literal("on").executes(ctx -> { Builder.I.towerOn = true; McApi.msg("Đã bật dựng cột leo."); return 1; }))
                    .then(ClientCommands.literal("off").executes(ctx -> { Builder.I.towerOn = false; McApi.msg("Đã tắt dựng cột leo."); return 1; })))
                .then(ClientCommands.literal("water").executes(ctx -> { refill(true); return 1; }))
                .then(ClientCommands.literal("lava").executes(ctx -> { refill(false); return 1; }))
                .then(ClientCommands.literal("start").executes(ctx -> { start(); return 1; }))
                .then(ClientCommands.literal("stop").executes(ctx -> { Builder.I.stop("Đã dừng."); return 1; }))
                .then(ClientCommands.literal("status").executes(ctx -> { McApi.msg(Builder.I.status()); return 1; }))));
    }

    private static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("schematics");
    }

    private static void list() {
        try (Stream<Path> s = Files.list(dir())) {
            McApi.msg("File trong " + dir() + ":");
            s.filter(f -> f.getFileName().toString().endsWith(".litematic"))
             .forEach(f -> McApi.msg(" - " + f.getFileName()));
        } catch (IOException e) {
            McApi.msg("Không đọc được thư mục schematics: " + e.getMessage());
        }
    }

    private static void load(String name) {
        if (!name.endsWith(".litematic")) name += ".litematic";
        try {
            McApi.msg(Builder.I.load(dir().resolve(name)));
        } catch (Exception e) {
            McApi.msg("Lỗi đọc file: " + e);
        }
    }

    private static BlockHitResult looking() {
        HitResult h = Minecraft.getInstance().hitResult;
        return (h instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) ? b : null;
    }

    private static void origin() {
        BlockHitResult b = looking();
        BlockPos o = b != null ? b.getBlockPos().relative(b.getDirection()) : Minecraft.getInstance().player.blockPosition();
        Builder.I.setOrigin(o);
        McApi.msg("Gốc xây (góc nhỏ nhất của bản vẽ) = " + o.getX() + " " + o.getY() + " " + o.getZ());
    }

    private static void chest() {
        BlockHitResult b = looking();
        if (b == null) { McApi.msg("Hãy nhìn thẳng vào rương rồi gõ lại."); return; }
        Builder.I.setChest(b.getBlockPos());
        McApi.msg("Đã chọn rương tại " + b.getBlockPos().toShortString());
    }

    /** Nhìn vào một khối nguồn nước/lava (tia nhìn có tính cả chất lỏng) để dùng làm điểm múc xô. */
    private static void refill(boolean water) {
        Minecraft mc = Minecraft.getInstance();
        var p = mc.player;
        Vec3 eye = p.getEyePosition();
        Vec3 to = eye.add(p.getLookAngle().scale(6.0));
        BlockHitResult r = mc.level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, p));
        if (r.getType() != HitResult.Type.BLOCK
                || mc.level.getBlockState(r.getBlockPos()).getBlock() != (water ? Blocks.WATER : Blocks.LAVA)
                || !mc.level.getFluidState(r.getBlockPos()).isSource()) {
            McApi.msg("Hãy nhìn thẳng vào một khối " + (water ? "nước" : "lava") + " nguồn (đứng gần dưới 6 khối).");
            return;
        }
        Builder.I.setRefill(water ? Items.WATER_BUCKET : Items.LAVA_BUCKET, r.getBlockPos());
        McApi.msg("Điểm múc " + (water ? "nước" : "lava") + ": " + r.getBlockPos().toShortString());
    }

    private static void start() {
        if (!Builder.I.isLoaded()) { McApi.msg("Chưa nạp bản vẽ: dùng /ab load <tên file>"); return; }
        if (!Builder.I.hasOrigin()) { McApi.msg("Chưa đặt gốc: nhìn vào chỗ muốn xây rồi gõ /ab origin"); return; }
        Builder.I.start();
        McApi.msg("Bắt đầu xây. Gõ /ab stop để dừng. Mở GUI bất kỳ sẽ tạm dừng.");
    }
}
