package top.luotiany1.backup;

import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

public final class LuoBackup extends Plugin {
    private Configuration config;
    private volatile boolean stopping;
    private final AtomicBoolean busy = new AtomicBoolean();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private static final Set<String> PLAYER = new HashSet<>(Arrays.asList("playerdata", "stats", "advancements"));

    @Override public void onEnable() {
        try {
            Files.createDirectories(getDataFolder().toPath());
            Path file = getDataFolder().toPath().resolve("config.yml");
            if (!Files.exists(file)) try (InputStream in = getResourceAsStream("config.yml")) { Files.copy(in, file); }
            config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(file.toFile());
            getProxy().getPluginManager().registerCommand(this, new Command("luobackup", "luobackup.manual") {
                @Override public void execute(CommandSender sender, String[] args) {
                    if (args.length != 2 || !args[0].equalsIgnoreCase("now")) {
                        sender.sendMessage(new TextComponent("Usage: /luobackup now <server>")); return;
                    }
                    String name = args[1];
                    Configuration servers = config.getSection("servers");
                    if (!servers.getKeys().contains(name)) {
                        sender.sendMessage(new TextComponent("Unknown server: " + name + ". Configured: " + servers.getKeys())); return;
                    }
                    if (!busy.compareAndSet(false, true)) {
                        sender.sendMessage(new TextComponent("A backup is already running. Please retry after it finishes.")); return;
                    }
                    getLogger().info("Manual backup requested by " + sender.getName() + " for " + name);
                    sender.sendMessage(new TextComponent("Manual backup started for " + name + "."));
                    try {
                        getProxy().getScheduler().runAsync(LuoBackup.this, () -> {
                            try {
                                Path result = runServer(name, servers.getSection(name), true);
                                sender.sendMessage(new TextComponent("Manual backup completed: " + result));
                            } catch (Exception e) {
                                getLogger().warning("Manual backup " + name + " failed: " + e);
                                sender.sendMessage(new TextComponent("Manual backup failed for " + name + ": " + e.getMessage()));
                            } finally { busy.set(false); }
                        });
                    } catch (RuntimeException e) { busy.set(false); throw e; }
                }
            });
            getLogger().info("Configured servers: " + config.getSection("servers").getKeys());
            getLogger().info("Manual command: /luobackup now <server> (permission: luobackup.manual)");
            getProxy().getScheduler().schedule(this, this::check, 10, 60, TimeUnit.SECONDS);
            getLogger().info("LuoBackup enabled; configuration changes require a proxy restart.");
        } catch (Exception e) { getLogger().severe("Cannot start LuoBackup: " + e); }
    }
    @Override public void onDisable() { stopping = true; }
    private void check() {
        if (stopping) return;
        if (!busy.compareAndSet(false, true)) return;
        try {
            Configuration servers = config.getSection("servers");
            for (String name : servers.getKeys()) {
                if (stopping) return;
                try { runServer(name, servers.getSection(name), false); }
                catch (Exception e) { getLogger().warning("Automatic backup " + name + " failed: " + e); }
            }
        } finally { busy.set(false); }
    }
    private Path runServer(String name, Configuration c, boolean manual) throws IOException {
        if (!manual && !c.getBoolean("enabled", false)) return null;
        if (!name.matches("[A-Za-z0-9_-]+")) throw new IOException("Unsafe server name: " + name);
        long hours = c.getLong("interval-hours", 72);
        int slots = c.getInt("slots", 3);
        long limit = c.getLong("max-size-bytes", 0);
        String policy = c.getString("full", "delete-oldest");
        if (!manual && (hours < 1 || hours > Long.MAX_VALUE / 3600000L || slots < 1 || limit < 0 || !(policy.equals("stop") || policy.equals("delete-oldest"))))
            throw new IOException("Invalid interval-hours, slots, max-size-bytes or full policy");
        Path source = Paths.get(c.getString("directory", "")).toAbsolutePath().normalize();
        if (!Files.isDirectory(source) || !Files.isRegularFile(source.resolve("server.properties"))) throw new IOException("Invalid server directory: " + source);
        source = source.toRealPath();
        Path base = Paths.get(config.getString("backup-directory", "plugins/LuoBackup/backups")).toAbsolutePath().normalize();
        Files.createDirectories(base);
        base = base.toRealPath();
        Path dest = base.resolve(name).normalize();
        if (dest.startsWith(source) || source.startsWith(dest)) throw new IOException("Backup and server directories must not overlap");
        Files.createDirectories(dest);
        if (Files.isSymbolicLink(dest)) throw new IOException("Backup directory cannot be a symbolic link");
        if (!dest.toRealPath().startsWith(base)) throw new IOException("Backup directory escapes its configured root");
        if (manual) {
            dest = dest.resolve("manual");
            Files.createDirectories(dest);
            if (Files.isSymbolicLink(dest) || !dest.toRealPath().startsWith(base)) throw new IOException("Unsafe manual backup directory");
        }
        List<Path> old = manual ? Collections.emptyList() : archives(dest);
        if (!manual && !old.isEmpty() && System.currentTimeMillis() - Files.getLastModifiedTime(old.get(old.size()-1)).toMillis() < hours * 3600000L) return null;
        if (!manual && policy.equals("stop") && (old.size() >= slots || (limit > 0 && total(old) >= limit))) {
            getLogger().warning(name + ": automatic backup stopped because slot or size limit is full (" + old.size() + "/" + slots + " slots, " + total(old) + " bytes)"); return null;
        }
        if (manual) { limit = 0; slots = Integer.MAX_VALUE; }
        final long started = System.currentTimeMillis();
        final long[] counts = {0, 0};
        getLogger().info(name + ": starting " + (manual ? "manual full" : "automatic") + " backup; source=" + source + ", destination=" + dest);
        if (!manual) getLogger().info(name + ": existing=" + old.size() + "/" + slots + " slots, " + total(old) + " bytes; limit=" + (limit == 0 ? "unlimited" : limit + " bytes") + ", policy=" + policy);
        String[] worlds = {c.getString("world", "world"), c.getString("nether", "world_nether"), c.getString("end", "world_the_end")};
        boolean[] enabled = {manual || c.getBoolean("include.world", true), manual || c.getBoolean("include.nether", true), manual || c.getBoolean("include.end", true)};
        if (new HashSet<>(Arrays.asList(worlds)).size() != worlds.length) throw new IOException("World directory names must be distinct");
        boolean players = manual || c.getBoolean("include.player-data", true);
        LinkedHashSet<Path> selected = new LinkedHashSet<>();
        for (int i = 0; i < worlds.length; i++) {
            Path world = child(source, worlds[i]);
            if (Files.isSymbolicLink(world)) throw new IOException("World directory cannot be a symbolic link: " + world);
            if (enabled[i]) selected.add(world);
            else if (players) for (String p : PLAYER) { Path dir = world.resolve(p); if (Files.exists(dir)) selected.add(dir); }
        }
        if (manual || c.getBoolean("include.plugins", true)) selected.add(child(source, "plugins"));
        for (Path path : selected) if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Selected directory is missing: " + path);
        if (selected.isEmpty()) throw new IOException("Nothing selected for backup");
        getLogger().info(name + ": selected paths=" + selected);
        Path temp = Files.createTempFile(dest, ".backup-", ".tmp");
        try {
            final Path root = source;
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                for (Path path : selected) Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (stopping) throw new IOException("Proxy is shutting down");
                        if (Files.isSymbolicLink(dir)) throw new IOException("Symbolic links are not supported: " + dir);
                        if (!players && PLAYER.contains(dir.getFileName().toString())) {
                            for (String world : worlds) if (dir.equals(root.resolve(world).resolve(dir.getFileName()))) return FileVisitResult.SKIP_SUBTREE;
                        }
                        String entry = root.relativize(dir).toString().replace(File.separatorChar, '/') + "/";
                        zip.putNextEntry(new ZipEntry(entry)); zip.closeEntry(); return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (stopping) throw new IOException("Proxy is shutting down");
                        if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) throw new IOException("Unsupported file: " + file);
                        for (String world : worlds) {
                            if (file.equals(root.resolve(world).resolve("session.lock"))) {
                                getLogger().info(name + ": skipping runtime world lock: " + file);
                                return FileVisitResult.CONTINUE;
                            }
                        }
                        zip.putNextEntry(new ZipEntry(root.relativize(file).toString().replace(File.separatorChar, '/')));
                        try { Files.copy(file, zip); }
                        catch (IOException e) { throw new IOException("Cannot read or archive " + file + ": " + e.getMessage(), e); }
                        zip.closeEntry();
                        counts[0]++; counts[1] += attrs.size();
                        if (counts[0] % 1000 == 0) getLogger().info(name + ": archived " + counts[0] + " files, " + counts[1] + " source bytes");
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                        throw new IOException("Cannot access " + file + ": " + e.getMessage(), e);
                    }
                });
            }
            long size = Files.size(temp);
            if (limit > 0 && size > limit) throw new IOException("New ZIP exceeds the total size limit; existing backups retained");
            long used = total(old);
            int remove = 0;
            while (old.size() - remove + 1 > slots || (limit > 0 && used > limit - size)) {
                if (policy.equals("stop")) throw new IOException("New ZIP would exceed slot or size limit; existing backups retained");
                used -= Files.size(old.get(remove++));
            }
            Path complete = dest.resolve("backup-" + STAMP.format(Instant.now()) + "-" + UUID.randomUUID() + ".zip");
            Files.move(temp, complete);
            for (int i = 0; i < remove; i++) { Files.delete(old.get(i)); getLogger().info(name + ": removed oldest backup " + old.get(i)); }
            getLogger().info(name + ": completed " + (manual ? "manual" : "automatic") + " backup: " + complete + "; zip=" + size + " bytes, files=" + counts[0] + ", source=" + counts[1] + " bytes, elapsed=" + (System.currentTimeMillis() - started) + " ms");
            return complete;
        } finally { Files.deleteIfExists(temp); }
    }
    private static Path child(Path root, String value) throws IOException {
        Path rel = Paths.get(value);
        if (rel.isAbsolute() || rel.getNameCount() != 1 || value.equals(".") || value.equals("..")) throw new IOException("World must be a single directory name: " + value);
        return root.resolve(rel);
    }
    private static List<Path> archives(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "backup-*.zip")) {
            for (Path p : stream) if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) out.add(p);
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString())); return out;
    }
    private static long total(List<Path> files) throws IOException {
        long total = 0; for (Path p : files) total = Math.addExact(total, Files.size(p)); return total;
    }
}
