package org.mingzu.trajectory;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Trajectory extends JavaPlugin implements CommandExecutor, Listener {

    private final Set<UUID> enabledPlayers = new HashSet<>();
    private final Map<UUID, UUID> lastAimedEntity = new HashMap<>();

    private double particleDensity;
    private int simulationTicks;
    private double startDistance;
    private boolean enableHitbox;
    private Particle hitboxParticle;
    private boolean showEntityInfo;
    private boolean globalParticles;
    private Sound aimSound;
    private Sound hitSound;

    private final Map<Material, Particle> trailParticles = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        getCommand("trajectory").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID uuid : enabledPlayers) {
                    Player p = getServer().getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        processTrajectory(p);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player p = (Player) sender;

        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("on")) {
                enabledPlayers.add(p.getUniqueId());
                p.sendMessage(ChatColor.GREEN + ">> Trajectory mode ON");
            } else if (args[0].equalsIgnoreCase("off")) {
                enabledPlayers.remove(p.getUniqueId());
                p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
                lastAimedEntity.remove(p.getUniqueId());
                p.sendMessage(ChatColor.RED + ">> Trajectory mode OFF");
            } else if (args[0].equalsIgnoreCase("reload") && p.hasPermission("trajectory.admin")) {
                reloadConfig();
                loadConfigValues();
                p.sendMessage(ChatColor.YELLOW + ">> Trajectory: Reloaded config");
            }
        }
        return true;
    }

    private void loadConfigValues() {
        particleDensity = getConfig().getDouble("particle-density", 0.5);
        simulationTicks = getConfig().getInt("simulation-ticks", 150);
        startDistance = getConfig().getDouble("start-distance", 1.0);
        enableHitbox = getConfig().getBoolean("enable-hitbox", true);
        hitboxParticle = getParticleSafe(getConfig().getString("hitbox-particle"), Particle.REDSTONE);
        showEntityInfo = getConfig().getBoolean("show-entity-info", true);
        globalParticles = getConfig().getBoolean("global-particles", false);

        aimSound = getSoundSafe(getConfig().getString("aim-sound", "BLOCK_NOTE_BLOCK_PLING"));
        hitSound = getSoundSafe(getConfig().getString("hit-sound", "ENTITY_ARROW_HIT_PLAYER"));

        trailParticles.clear();
        trailParticles.put(Material.BOW, getParticleSafe(getConfig().getString("trails.bow"), Particle.REDSTONE));
        trailParticles.put(Material.CROSSBOW, getParticleSafe(getConfig().getString("trails.crossbow"), Particle.REDSTONE));
        trailParticles.put(Material.TRIDENT, getParticleSafe(getConfig().getString("trails.trident"), Particle.REDSTONE));
        trailParticles.put(Material.SNOWBALL, getParticleSafe(getConfig().getString("trails.snowball"), Particle.REDSTONE));
        trailParticles.put(Material.EGG, getParticleSafe(getConfig().getString("trails.egg"), Particle.REDSTONE));
        trailParticles.put(Material.ENDER_PEARL, getParticleSafe(getConfig().getString("trails.ender_pearl"), Particle.REDSTONE));
        trailParticles.put(Material.SPLASH_POTION, getParticleSafe(getConfig().getString("trails.potion"), Particle.REDSTONE));
        trailParticles.put(Material.LINGERING_POTION, getParticleSafe(getConfig().getString("trails.potion"), Particle.REDSTONE));
    }

    private Particle getParticleSafe(String name, Particle fallback) {
        if (name == null || name.equalsIgnoreCase("false")) return fallback;
        try {
            return Particle.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private Sound getSoundSafe(String name) {
        if (name == null || name.equalsIgnoreCase("false")) return null;
        try {
            return Sound.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Sound.BLOCK_NOTE_BLOCK_PLING;
        }
    }

    private void processTrajectory(Player p) {
        Material item = p.getInventory().getItemInMainHand().getType();

        double velocityMult = 0.0;
        double gravity = 0.0;
        double drag = 0.99;
        boolean isPotion = false;

        if (item == Material.BOW || item == Material.CROSSBOW) {
            velocityMult = 3.0;
            gravity = 0.05;
        } else if (item == Material.TRIDENT) {
            velocityMult = 2.5;
            gravity = 0.05;
        } else if (item == Material.SNOWBALL || item == Material.EGG || item == Material.ENDER_PEARL) {
            velocityMult = 1.5;
            gravity = 0.03;
        } else if (item == Material.SPLASH_POTION || item == Material.LINGERING_POTION) {
            velocityMult = 0.5;
            gravity = 0.05;
            isPotion = true;
        } else {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
            lastAimedEntity.remove(p.getUniqueId());
            return;
        }

        Particle trail = trailParticles.getOrDefault(item, Particle.REDSTONE);
        Location loc = p.getEyeLocation();
        Vector direction = loc.getDirection().normalize();
        loc.add(direction.clone().multiply(startDistance));
        Vector velocity = direction.multiply(velocityMult);
        Entity hitEntity = null;
        Location finalLoc = null;

        for (int i = 0; i < simulationTicks; i++) {
            Vector startVec = loc.toVector();
            loc.add(velocity);
            Vector endVec = loc.toVector();

            drawLine(p, startVec.toLocation(p.getWorld()), endVec.toLocation(p.getWorld()), trail, particleDensity, Color.RED);

            Vector dir = velocity.clone().normalize();
            double rayLength = velocity.length();

            BoundingBox searchBox = BoundingBox.of(startVec, endVec).expand(1.0);
            for (Entity entity : loc.getWorld().getNearbyEntities(searchBox)) {
                if (entity != p && entity instanceof LivingEntity) {
                    RayTraceResult hitResult = entity.getBoundingBox().rayTrace(startVec, dir, rayLength);
                    if (hitResult != null) {
                        hitEntity = entity;
                        finalLoc = hitResult.getHitPosition().toLocation(p.getWorld());
                        break;
                    }
                }
            }

            if (hitEntity != null) break;

            velocity.multiply(drag);
            velocity.setY(velocity.getY() - gravity);

            if (loc.getBlock().getType().isSolid()) {
                finalLoc = loc.clone();
                break;
            }
        }

        if (hitEntity != null) {
            if (enableHitbox) {
                if (isPotion) {
                    drawCircle(p, hitEntity.getLocation(), 4.0, hitboxParticle, Color.LIME);
                } else {
                    highlightHitbox(p, hitEntity);
                }
            }

            if (showEntityInfo) {
                LivingEntity le = (LivingEntity) hitEntity;
                String name = le instanceof Player ? le.getName() : le.getType().name();
                double hp = Math.round(le.getHealth() * 10.0) / 10.0;
                String msg = ChatColor.RED + "Target: " + ChatColor.YELLOW + name + ChatColor.WHITE + " | " + ChatColor.RED + "HP: " + hp;
                p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
            }

            UUID lastAimed = lastAimedEntity.get(p.getUniqueId());
            if (lastAimed == null || !lastAimed.equals(hitEntity.getUniqueId())) {
                if (aimSound != null) {
                    p.playSound(p.getLocation(), aimSound, 1.0f, 2.0f);
                }
                lastAimedEntity.put(p.getUniqueId(), hitEntity.getUniqueId());
            }

        } else {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
            lastAimedEntity.remove(p.getUniqueId());

            if (finalLoc != null) {
                if (isPotion && enableHitbox) {
                    drawCircle(p, finalLoc, 4.0, hitboxParticle, Color.LIME);
                } else {
                    try {
                        Particle.DustOptions hitWallDust = new Particle.DustOptions(Color.ORANGE, 1.5f);
                        spawnParticleLogic(p, Particle.REDSTONE, finalLoc, hitWallDust);
                    } catch (Exception ignored) {
                        spawnParticleLogic(p, Particle.FLAME, finalLoc, null);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        if (e.getHitEntity() != null && e.getEntity().getShooter() instanceof Player) {
            Player p = (Player) e.getEntity().getShooter();
            if (enabledPlayers.contains(p.getUniqueId()) && hitSound != null) {
                p.playSound(p.getLocation(), hitSound, 1.0f, 1.0f);
            }
        }
    }

    private void drawCircle(Player p, Location center, double radius, Particle particle, Color color) {
        double d = 0.2;
        double points = (2 * Math.PI * radius) / d;
        double increment = (2 * Math.PI) / points;

        Location drawLoc = center.clone().add(0, 0.5, 0);

        for (double i = 0; i < Math.PI * 2; i += increment) {
            double x = radius * Math.cos(i);
            double z = radius * Math.sin(i);
            drawLoc.add(x, 0, z);

            try {
                if (particle.getDataType() == Particle.DustOptions.class) {
                    Particle.DustOptions dust = new Particle.DustOptions(color, 1.0f);
                    spawnParticleLogic(p, particle, drawLoc, dust);
                } else {
                    spawnParticleLogic(p, particle, drawLoc, null);
                }
            } catch (Exception ignored) {}

            drawLoc.subtract(x, 0, z);
        }
    }

    private void highlightHitbox(Player viewer, Entity target) {
        BoundingBox box = target.getBoundingBox();

        double minX = box.getMinX(); double minY = box.getMinY(); double minZ = box.getMinZ();
        double maxX = box.getMaxX(); double maxY = box.getMaxY(); double maxZ = box.getMaxZ();
        double d = 0.25;

        drawLine(viewer, new Location(target.getWorld(), minX, minY, minZ), new Location(target.getWorld(), minX, maxY, minZ), hitboxParticle, d, Color.LIME);
        drawLine(viewer, new Location(target.getWorld(), maxX, minY, minZ), new Location(target.getWorld(), maxX, maxY, minZ), hitboxParticle, d, Color.LIME);
        drawLine(viewer, new Location(target.getWorld(), minX, minY, maxZ), new Location(target.getWorld(), minX, maxY, maxZ), hitboxParticle, d, Color.LIME);
        drawLine(viewer, new Location(target.getWorld(), maxX, minY, maxZ), new Location(target.getWorld(), maxX, maxY, maxZ), hitboxParticle, d, Color.LIME);

        drawLine(viewer, new Location(target.getWorld(), minX, minY, minZ), new Location(target.getWorld(), maxX, minY, minZ), hitboxParticle, d, Color.LIME);
        drawLine(viewer, new Location(target.getWorld(), minX, minY, maxZ), new Location(target.getWorld(), maxX, minY, maxZ), hitboxParticle, d, Color.LIME);
        drawLine(viewer, new Location(target.getWorld(), minX, minY, minZ), new Location(target.getWorld(), minX, minY, maxZ), hitboxParticle, d, Color.LIME);
        drawLine(viewer, new Location(target.getWorld(), maxX, minY, minZ), new Location(target.getWorld(), maxX, minY, maxZ), hitboxParticle, d, Color.LIME);

        drawLine(viewer, new Location(target.getWorld(), minX, maxY, minZ), new Location(target.getWorld(), maxX, maxY, minZ), hitboxParticle, d, Color.LIME);
        drawLine(viewer, new Location(target.getWorld(), minX, maxY, maxZ), new Location(target.getWorld(), maxX, maxY, maxZ), hitboxParticle, d, Color.LIME);
        drawLine(viewer, new Location(target.getWorld(), minX, maxY, minZ), new Location(target.getWorld(), minX, maxY, maxZ), hitboxParticle, d, Color.LIME);
        drawLine(viewer, new Location(target.getWorld(), maxX, maxY, minZ), new Location(target.getWorld(), maxX, maxY, maxZ), hitboxParticle, d, Color.LIME);
    }

    private void drawLine(Player p, Location start, Location end, Particle particle, double density, Color dustColor) {
        Vector vector = end.toVector().subtract(start.toVector());
        double length = vector.length();
        vector.normalize();

        if (density <= 0) density = 0.5;

        for (double i = 0; i < length; i += density) {
            Vector offset = vector.clone().multiply(i);
            Location point = start.clone().add(offset);

            try {
                if (particle.getDataType() == Particle.DustOptions.class) {
                    Particle.DustOptions dust = new Particle.DustOptions(dustColor, 0.8f);
                    spawnParticleLogic(p, particle, point, dust);
                } else {
                    spawnParticleLogic(p, particle, point, null);
                }
            } catch (Exception ignored) {}
        }
    }

    private void spawnParticleLogic(Player p, Particle particle, Location loc, Object data) {
        if (globalParticles) {
            if (data != null) {
                loc.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0, data);
            } else {
                loc.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
            }
        } else {
            if (data != null) {
                p.spawnParticle(particle, loc, 1, 0, 0, 0, 0, data);
            } else {
                p.spawnParticle(particle, loc, 1, 0, 0, 0, 0);
            }
        }
    }
}