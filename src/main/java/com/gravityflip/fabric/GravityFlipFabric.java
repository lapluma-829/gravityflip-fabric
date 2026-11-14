package com.gravityflip.fabric;

import gravity_changer.api.GravityChangerAPI;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Direction;
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

    @Override
    public void onInitialize() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register(this::onAfterDamage);
        LOGGER.info("Gravity Flip 已加载（依赖 Gravity Changer 提供物理）");
    }

    private void onAfterDamage(LivingEntity entity, DamageSource source, float baseDamage,
                               float damageTaken, boolean blocked) {
        if (!(entity instanceof ServerPlayerEntity player) || damageTaken <= 0.0F) {
            return;
        }
        long now = player.getWorld().getTime();
        Long last = LAST_FLIP.get(player.getUuid());
        if (last != null && now - last < FLIP_COOLDOWN_TICKS) {
            return;
        }
        LAST_FLIP.put(player.getUuid(), now);

        Direction current = GravityChangerAPI.getBaseGravityDirection(player);
        Direction next = randomOther(current);
        GravityChangerAPI.setBaseGravityDirection(player, next);
        LOGGER.info("{} 的重力翻转为 {}", player.getGameProfile().getName(), label(next));
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
