package top.luotiany1.backup;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/** A local rendezvous avoids plugin messaging's requirement for an online player. */
public final class ServerBackup extends JavaPlugin {
    private Path control;
    private String last = "";
    @Override public void onEnable() {
        control = new File(getDataFolder(), "control").toPath();
        try { Files.createDirectories(control); } catch (Exception e) { throw new IllegalStateException(e); }
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            Path request = control.resolve("request.txt");
            try {
                if (!Files.exists(request)) return;
                String id = new String(Files.readAllBytes(request), StandardCharsets.UTF_8).trim();
                UUID.fromString(id);
                if (id.equals(last)) return;
                last = id;
                Bukkit.savePlayers();
                for (World world : Bukkit.getWorlds()) world.save();
                Path temp = control.resolve("ack.tmp");
                Files.write(temp, id.getBytes(StandardCharsets.UTF_8));
                Files.move(temp, control.resolve("ack.txt"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) { getLogger().warning("Save request failed: " + e.getMessage()); }
        }, 20L, 20L);
    }
}
