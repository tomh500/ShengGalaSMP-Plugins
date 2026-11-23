package top.luotiany1.bwcombat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

public final class BwLegacyCombat extends JavaPlugin implements Listener {
    private static final AttributeModifier FAST_ATTACKS = new AttributeModifier(
            UUID.fromString("1e47505b-d49d-4ecf-bc2e-5b95f583945c"), "BW legacy attack speed",
            1024, AttributeModifier.Operation.ADD_NUMBER);

    private final Map<UUID, Vector> pendingKnockback = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getOnlinePlayers().forEach(this::applyAttackSpeed);
        getLogger().info("Enabled 1.8 melee knockback and full-speed attacks for BW");
    }

    @Override
    public void onDisable() {
        getServer().getOnlinePlayers().forEach(this::removeAttackSpeed);
        pendingKnockback.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        applyAttackSpeed(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeAttackSpeed(event.getPlayer());
        pendingKnockback.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        getServer().getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                applyAttackSpeed(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMeleeHit(EntityDamageByEntityEvent event) {
        if (event.getCause() != DamageCause.ENTITY_ATTACK || !(event.getDamager() instanceof Player)
                || !(event.getEntity() instanceof Player)) {
            return;
        }
        Player attacker = (Player) event.getDamager();
        Player victim = (Player) event.getEntity();
        UUID victimId = victim.getUniqueId();
        Vector velocity = legacyKnockback(attacker, victim);
        pendingKnockback.put(victimId, velocity);
        getServer().getScheduler().runTask(this, () -> pendingKnockback.remove(victimId, velocity));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVelocity(PlayerVelocityEvent event) {
        Vector legacyVelocity = pendingKnockback.remove(event.getPlayer().getUniqueId());
        if (legacyVelocity != null && !event.isCancelled()) {
            event.setVelocity(legacyVelocity);
        }
    }

    private void applyAttackSpeed(Player player) {
        AttributeInstance speed = player.getAttribute(Attribute.GENERIC_ATTACK_SPEED);
        speed.removeModifier(FAST_ATTACKS);
        speed.addModifier(FAST_ATTACKS);
    }

    private void removeAttackSpeed(Player player) {
        player.getAttribute(Attribute.GENERIC_ATTACK_SPEED).removeModifier(FAST_ATTACKS);
    }

    private static Vector legacyKnockback(Player attacker, Player victim) {
        Location source = attacker.getLocation();
        Location target = victim.getLocation();
        double angle = Math.toRadians(source.getYaw());
        double forwardX = -Math.sin(angle);
        double forwardZ = Math.cos(angle);
        double deltaX = target.getX() - source.getX();
        double deltaZ = target.getZ() - source.getZ();
        double distance = Math.hypot(deltaX, deltaZ);
        double awayX = distance > 0.0001 ? deltaX / distance : forwardX;
        double awayZ = distance > 0.0001 ? deltaZ / distance : forwardZ;

        int extra = attacker.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.KNOCKBACK);
        if (attacker.isSprinting()) {
            extra++;
        }
        Vector previous = victim.getVelocity();
        return new Vector(
                previous.getX() / 2 + awayX * 0.4 + forwardX * 0.5 * extra,
                Math.min(previous.getY() / 2 + 0.4, 0.4) + 0.1 * extra,
                previous.getZ() / 2 + awayZ * 0.4 + forwardZ * 0.5 * extra);
    }
}
