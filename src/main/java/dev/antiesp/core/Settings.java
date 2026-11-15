package dev.antiesp.core;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/** Immutable snapshot of config.yml. Safe to read from netty threads. */
public final class Settings {

    public final Set<String> disabledWorlds = new HashSet<>();
    public final String bypassPermission;
    public final boolean spectatorsBypass;

    // entities
    public final boolean entitiesEnabled;
    public final Set<org.bukkit.entity.EntityType> bukkitEntityTypes = EnumSet.noneOf(org.bukkit.entity.EntityType.class);
    public final Set<EntityType> packetEntityTypes = new HashSet<>();
    public final boolean onlyPlayers;
    public final int entityInterval;
    public final double entityMaxDistance;
    public final double entityAlwaysVisible;
    public final int hideAfterChecks;
    public final double boxExpand;
    public final double lookaheadTicks;
    public final boolean thirdPerson;
    public final double thirdPersonDistance;
    public final boolean showGlowing;

    // blocks
    public final boolean blocksEnabled;
    public final Set<Material> hiddenMaterials = EnumSet.noneOf(Material.class);
    public final Set<StateType> hiddenStateTypes = new HashSet<>();
    public final int blockInterval;
    public final double blockMaxDistance;
    public final double blockAlwaysVisible;
    public final boolean rehideBlocks;
    private final String replacementMode;
    private final int fixedReplacementId;
    private final int stoneId, deepslateId, netherrackId, endStoneId;

    // health
    public final boolean healthEnabled;
    public final boolean healthPlayersOnly;
    public final float healthSpoofValue;
    public final boolean hideAbsorption;

    // sounds
    public final boolean maskSounds;
    public final double soundRadius;

    public Settings(FileConfiguration c, Logger log) {
        c.getStringList("disabled-worlds").forEach(w -> disabledWorlds.add(w.toLowerCase(Locale.ROOT)));
        bypassPermission = c.getString("bypass-permission", "antiesp.bypass");
        spectatorsBypass = c.getBoolean("spectators-bypass", true);

        ConfigurationSection e = section(c, "entities");
        entitiesEnabled = e.getBoolean("enabled", true);
        for (String name : e.getStringList("types")) {
            try {
                org.bukkit.entity.EntityType t = org.bukkit.entity.EntityType.valueOf(name.toUpperCase(Locale.ROOT));
                bukkitEntityTypes.add(t);
                EntityType pt = SpigotConversionUtil.fromBukkitEntityType(t);
                if (pt != null) packetEntityTypes.add(pt);
            } catch (IllegalArgumentException ex) {
                log.warning("Unknown entity type in config: " + name);
            }
        }
        onlyPlayers = bukkitEntityTypes.size() == 1 && bukkitEntityTypes.contains(org.bukkit.entity.EntityType.PLAYER);
        entityInterval = Math.max(1, e.getInt("check-interval", 2));
        entityMaxDistance = e.getDouble("max-distance", 64);
        entityAlwaysVisible = e.getDouble("always-visible-distance", 3.0);
        hideAfterChecks = Math.max(1, e.getInt("hide-after-checks", 2));
        boxExpand = e.getDouble("box-expand", 0.6);
        lookaheadTicks = e.getDouble("movement-lookahead-ticks", 3);
        thirdPerson = e.getBoolean("third-person-camera", true);
        thirdPersonDistance = e.getDouble("third-person-distance", 4.0);
        showGlowing = e.getBoolean("always-show-glowing", true);

        ConfigurationSection b = section(c, "blocks");
        blocksEnabled = b.getBoolean("enabled", true);
        for (String name : b.getStringList("types")) {
            Material m = Material.matchMaterial(name);
            if (m == null || !m.isBlock()) {
                log.warning("Unknown block type in config: " + name);
                continue;
            }
            hiddenMaterials.add(m);
            StateType st = SpigotConversionUtil.fromBukkitBlockData(m.createBlockData()).getType();
            hiddenStateTypes.add(st);
        }
        blockInterval = Math.max(1, b.getInt("check-interval", 4));
        blockMaxDistance = b.getDouble("max-distance", 64);
        blockAlwaysVisible = b.getDouble("always-visible-distance", 4.0);
        rehideBlocks = b.getBoolean("rehide", true);

        stoneId = idOf(StateTypes.STONE);
        deepslateId = idOf(StateTypes.DEEPSLATE);
        netherrackId = idOf(StateTypes.NETHERRACK);
        endStoneId = idOf(StateTypes.END_STONE);
        String mode = b.getString("replacement", "auto").toLowerCase(Locale.ROOT);
        int fixed = stoneId;
        if (!mode.equals("auto")) {
            Material m = Material.matchMaterial(mode);
            if (m != null && m.isBlock()) {
                fixed = SpigotConversionUtil.fromBukkitBlockData(m.createBlockData()).getGlobalId();
            } else {
                log.warning("Unknown replacement block '" + mode + "', using auto");
                mode = "auto";
            }
        }
        replacementMode = mode;
        fixedReplacementId = fixed;

        ConfigurationSection h = section(c, "health");
        healthEnabled = h.getBoolean("enabled", true);
        healthPlayersOnly = h.getBoolean("players-only", true);
        healthSpoofValue = (float) h.getDouble("spoof-value", 20.0);
        hideAbsorption = h.getBoolean("hide-absorption", true);

        ConfigurationSection s = section(c, "sounds");
        maskSounds = s.getBoolean("mask-near-hidden-entities", true);
        soundRadius = s.getDouble("radius", 2.0);
    }

    private static ConfigurationSection section(FileConfiguration c, String path) {
        ConfigurationSection s = c.getConfigurationSection(path);
        return s != null ? s : c.createSection(path);
    }

    private static int idOf(StateType type) {
        return WrappedBlockState.getDefaultState(type).getGlobalId();
    }

    /** Block state id the client sees instead of a hidden storage block. */
    public int replacementFor(World.Environment env, int y) {
        if (!replacementMode.equals("auto")) return fixedReplacementId;
        return switch (env) {
            case NETHER -> netherrackId;
            case THE_END -> endStoneId;
            default -> y < 0 ? deepslateId : stoneId;
        };
    }

    public boolean isWorldDisabled(World world) {
        return disabledWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }
}
