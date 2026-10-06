package com.example.automine;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.CommandSource;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Client-side auto miner.
 *   /automine <block> [amount]   e.g. /automine diamond_ore 10
 *   /automine stop
 * Finds the nearest matching block, looks at it, walks toward it,
 * tunnels through anything in the way, and mines it with the best hotbar tool.
 */
public class AutoMineMod implements ClientModInitializer {
    private static final int SEARCH_RADIUS = 24;
    private static final double REACH = 4.5;

    private static boolean active = false;
    private static Block targetBlock = null;
    private static int remaining = 0;
    private static BlockPos current = null;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("automine")
                .then(literal("stop").executes(ctx -> {
                    stop(ctx.getSource(), "Stopped.");
                    return 1;
                }))
                .then(argument("block", StringArgumentType.word())
                    .suggests((c, b) -> CommandSource.suggestIdentifiers(Registries.BLOCK.getIds(), b))
                    .executes(ctx -> start(ctx.getSource(), StringArgumentType.getString(ctx, "block"), 1))
                    .then(argument("amount", IntegerArgumentType.integer(1, 10000))
                        .executes(ctx -> start(ctx.getSource(),
                            StringArgumentType.getString(ctx, "block"),
                            IntegerArgumentType.getInteger(ctx, "amount"))))));
        });

        ClientTickEvents.END_CLIENT_TICK.register(AutoMineMod::tick);
    }

    private static int start(FabricClientCommandSource src, String name, int amount) {
        Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            src.sendError(Text.literal("Unknown block: " + name));
            return 0;
        }
        targetBlock = Registries.BLOCK.get(id);
        remaining = amount;
        current = null;
        active = true;
        src.sendFeedback(Text.literal("Mining " + amount + "x " + id + "... (/automine stop to cancel)"));
        return 1;
    }

    private static void stop(FabricClientCommandSource src, String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        active = false;
        current = null;
        if (mc.options != null) {
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
        }
        if (src != null) src.sendFeedback(Text.literal(msg));
        else if (mc.player != null) mc.player.sendMessage(Text.literal(msg), false);
    }

    private static void tick(MinecraftClient mc) {
        if (!active) return;
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null || mc.interactionManager == null) {
            active = false;
            return;
        }

        // Did the current target just get mined?
        if (current != null && mc.world.getBlockState(current).getBlock() != targetBlock) {
            current = null;
            remaining--;
            if (remaining <= 0) {
                stop(null, "Done mining.");
                return;
            }
        }

        // Pick a new target
        if (current == null) {
            current = findNearest(mc, player);
            if (current == null) {
                stop(null, "No more of that block within " + SEARCH_RADIUS + " blocks.");
                return;
            }
        }

        // Aim at the target
        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(current);
        double dx = center.x - eye.x, dy = center.y - eye.y, dz = center.z - eye.z;
        player.setYaw((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f);
        player.setPitch((float) -(MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI));

        // What is between us and the target? (could be the target itself)
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
            eye, center, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            mc.options.forwardKey.setPressed(true);
            return;
        }

        BlockPos breakPos = hit.getBlockPos();
        double dist = eye.distanceTo(Vec3d.ofCenter(breakPos));

        if (dist <= REACH) {
            // In reach: stop walking, equip best tool, dig
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            equipBestTool(player, mc.world.getBlockState(breakPos));
            mc.interactionManager.updateBlockBreakingProgress(breakPos, hit.getSide());
            player.swingHand(Hand.MAIN_HAND);
        } else {
            // Too far: walk toward it, hop over small obstacles
            mc.options.forwardKey.setPressed(true);
            mc.options.jumpKey.setPressed(player.horizontalCollision && player.isOnGround());
        }
    }

    private static BlockPos findNearest(MinecraftClient mc, ClientPlayerEntity player) {
        BlockPos origin = player.getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -SEARCH_RADIUS; y <= SEARCH_RADIUS; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    m.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (mc.world.getBlockState(m).getBlock() == targetBlock) {
                        double d = m.getSquaredDistance(origin);
                        if (d < bestDist) {
                            bestDist = d;
                            best = m.toImmutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    private static void equipBestTool(ClientPlayerEntity player, BlockState state) {
        int bestSlot = player.getInventory().getSelectedSlot();
        float bestSpeed = player.getInventory().getStack(bestSlot).getMiningSpeedMultiplier(state);
        for (int i = 0; i < 9; i++) {
            float s = player.getInventory().getStack(i).getMiningSpeedMultiplier(state);
            if (s > bestSpeed) {
                bestSpeed = s;
                bestSlot = i;
            }
        }
        player.getInventory().setSelectedSlot(bestSlot);
    }
}
