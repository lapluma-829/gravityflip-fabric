package com.gravityflip.fabric;

import gravity_changer.api.GravityChangerAPI;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 受伤后把重力随机翻到另一个方向。
 *
 * 真正的重力实现（六向重力、天花板/墙面行走、跳跃、视角跟随、液体、联机同步）
 * 全部由 Gravity Changer (qouteall fork) 负责，这里只做"受伤触发"这一件事。
 */
public class GravityFlipFabric implements ModInitializer {
    public static final String MOD_ID = "gravityflip";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Direction[] DIRECTIONS = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final String[] LABELS = {"下", "上", "北", "南", "西", "东"};

    /** 同一 tick 内多重伤害（火焰+近战）只翻一次。 */
    private static final int FLIP_COOLDOWN_TICKS = 8;
    private static final Map<UUID, Long> LAST_FLIP = new ConcurrentHashMap<>();
    /** 沿重力方向累计的「坠落」距离，用于自定义坠落伤害。 */
    private static final Map<UUID, Double> FALL_DISTANCE = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        // ALLOW_DAMAGE 在 1.20.1 的 Fabric API 里可用（AFTER_DAMAGE 是更晚版本才有的）。
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(this::onAllowDamage);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        LOGGER.info("Gravity Flip 已加载（依赖 Gravity Changer 提供物理）");
    }

    private void onServerTick(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            applyFallDamage(player);
        }
    }

    /**
     * 原版坠落伤害只看世界 Y 轴，重力换个方向就不会触发。
     * 这里沿实际重力方向累计距离，真正落地时按原版公式扣血。
     */
    private void applyFallDamage(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        Direction direction = GravityChangerAPI.getGravityDirection(player);
        if (direction == Direction.DOWN || player.isCreative() || player.isSpectator()
                || player.isTouchingWater() || player.isInLava() || player.isFallFlying()) {
            FALL_DISTANCE.put(id, 0.0D);
            return;
        }
        Vec3d velocity = player.getVelocity();
        double along = velocity.x * direction.getOffsetX()
                + velocity.y * direction.getOffsetY()
                + velocity.z * direction.getOffsetZ();
        double distance = FALL_DISTANCE.getOrDefault(id, 0.0D);
        if (player.isOnGround()) {
            if (distance > 3.0D) {
                player.damage(player.getDamageSources().fall(), (float) (distance - 3.0D));
            }
            FALL_DISTANCE.put(id, 0.0D);
        } else if (along > 0.0D) {
            FALL_DISTANCE.put(id, distance + along);
        }
    }

    private boolean onAllowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (entity instanceof ServerPlayerEntity player && amount > 0.0F) {
            long now = player.getWorld().getTime();
            Long last = LAST_FLIP.get(player.getUuid());
            if (last == null || now - last >= FLIP_COOLDOWN_TICKS) {
                LAST_FLIP.put(player.getUuid(), now);
                Direction current = GravityChangerAPI.getBaseGravityDirection(player);
                Direction next = randomOther(current);
                GravityChangerAPI.setBaseGravityDirection(player, next);
                LOGGER.info("{} 的重力翻转为 {}", player.getGameProfile().getName(), label(next));
            }
        }
        return true; // 不阻止原本的伤害
    }

    private static Direction randomOther(Direction current) {
        Direction next;
        do {
            next = DIRECTIONS[ThreadLocalRandom.current().nextInt(DIRECTIONS.length)];
        } while (next == current);
        return next;
    }

    private static String label(Direction direction) {
        for (int i = 0; i < DIRECTIONS.length; i++) {
            if (DIRECTIONS[i] == direction) {
                return LABELS[i];
            }
        }
        return direction.toString();
    }
}
