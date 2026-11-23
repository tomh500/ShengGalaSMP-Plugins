package top.luotiany1.backup;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public final class ProxyBackup extends Plugin {
    private Configuration config;
    private final Set<String> running = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Map<String, String> fired = new ConcurrentHashMap<>();
    private Path root;
    @Override public void onEnable() {
        try {
            getDataFolder().mkdirs();
            File f = new File(getDataFolder(), "config.yml");
            if (!f.exists()) try (InputStream in = getResourceAsStream("config.yml")) { Files.copy(in, f.toPath()); }
            config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(f);
            root = Paths.get(config.getString("backup-directory", "backups")).toAbsolutePath().normalize();
            Files.createDirectories(root);
        } catch (Exception e) { throw new IllegalStateException("Cannot load LuoBackup configuration", e); }
        getProxy().getPluginManager().registerCommand(this, new Command("luobackup", "luobackup.admin") {
            @Override public void execute(CommandSender sender, String[] args) {
                if (args.length == 1 && args[0].equalsIgnoreCase("status")) { send(sender, "运行中: " + running); return; }
                if (args.length == 2 && args[0].equalsIgnoreCase("now")) {
                    Configuration servers = config.getSection("servers");
                    if (servers == null || !servers.getKeys().contains(args[1]) || !servers.getBoolean(args[1] + ".enabled", false)) { send(sender, "服务器未配置或未启用: " + args[1]); return; }
                    send(sender, start(args[1]) ? "已提交备份: " + args[1] : "此服务器已有备份任务"); return;
                }
                send(sender, "用法: /luobackup status | /luobackup now <服务器名>");
            }
        });
        getProxy().getScheduler().schedule(this, this::tick, 0, 15, TimeUnit.SECONDS);
    }
    private void send(CommandSender s, String m) { s.sendMessage(new TextComponent("§a[LuoBackup] §f" + m)); }
    private void tick() {
        Configuration servers = config.getSection("servers");
        if (servers == null) return;
        for (String name : servers.getKeys()) try {
            Configuration s = servers.getSection(name);
            if (!s.getBoolean("enabled", false)) continue;
            ZonedDateTime now = ZonedDateTime.now();
            String key;
            if (s.getString("schedule", "daily").equalsIgnoreCase("daily")) {
                LocalTime t = LocalTime.parse(s.getString("daily-time", "03:00"));
                if (now.getHour() != t.getHour() || now.getMinute() != t.getMinute()) continue;
                key = now.toLocalDate().toString();
            } else if (s.getString("schedule").equalsIgnoreCase("interval")) {
                int minutes = s.getInt("interval-minutes", 360);
                if (minutes <= 0) throw new IllegalArgumentException("interval-minutes must be positive");
                long minute = System.currentTimeMillis() / 60000L;
                if (minute % minutes != 0) continue;
                key = Long.toString(minute);
            } else throw new IllegalArgumentException("unknown schedule");
            if (!key.equals(fired.get(name))) { fired.put(name, key); start(name); }
        } catch (Exception e) { getLogger().warning("Invalid schedule for " + name + ": " + e.getMessage()); }
    }
    private boolean start(String name) {
        if (!running.add(name)) return false;
        getProxy().getScheduler().runAsync(this, () -> {
            try { backup(name, config.getSection("servers").getSection(name)); }
            catch (Exception e) { getLogger().warning("Backup failed for " + name + ": " + e); }
            finally { running.remove(name); }
        });
        return true;
    }
    private void backup(String name, Configuration s) throws Exception {
        if (!name.matches("[A-Za-z0-9_-]+") || getProxy().getServerInfo(name) == null) throw new IOException("Server name is invalid or missing from BungeeCord");
        int slots = s.getInt("max-slots", 10); long mb = s.getLong("max-size-mb", 0);
        if (slots < 1 || mb < 0 || mb > Long.MAX_VALUE / 1048576L) throw new IOException("Invalid limits");
        long limit = mb * 1048576L;
        String policy = s.getString("when-full", "stop");
        if (!policy.equals("stop") && !policy.equals("delete-oldest")) throw new IOException("Invalid when-full");
        Path server = Paths.get(s.getString("directory")).toAbsolutePath().normalize().toRealPath();
        if (root.startsWith(server)) throw new IOException("Backup directory cannot be inside the server directory");
        String world = s.getString("world", "world");
        if (world.isEmpty() || world.contains("/") || world.contains("\\") || world.equals(".") || world.equals("..")) throw new IOException("Invalid world name");
        Path out = root.resolve(name); Files.createDirectories(out);
        List<Path> old = archives(out);
        if (policy.equals("stop") && old.size() >= slots) throw new IOException("Slot limit reached");
        Path control = server.resolve("plugins/LuoBackup/control");
        if (!Files.isDirectory(control)) throw new IOException("Subserver plugin control directory missing");
        String id = UUID.randomUUID().toString();
        Path request = control.resolve("request.txt"), tempRequest = control.resolve("request." + id + ".tmp");
        Files.write(tempRequest, id.getBytes(StandardCharsets.UTF_8));
        Files.move(tempRequest, request, StandardCopyOption.REPLACE_EXISTING);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Path ack = control.resolve("ack.txt"); boolean ready = false;
        while (System.nanoTime() < deadline) {
            if (Files.exists(ack) && id.equals(new String(Files.readAllBytes(ack), StandardCharsets.UTF_8).trim())) { ready = true; break; }
            Thread.sleep(250);
        }
        if (!ready) throw new IOException("Subserver save timed out; is it online?");
        Path temp = out.resolve("." + id + ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW)))) {
                Configuration inc = s.getSection("include");
                Set<String> player = new HashSet<>(Arrays.asList("playerdata", "advancements", "stats"));
                if (inc.getBoolean("overworld", true)) add(server, server.resolve(world), zip, player);
                if (inc.getBoolean("nether", true)) add(server, server.resolve(world + "_nether"), zip, Collections.<String>emptySet());
                if (inc.getBoolean("end", true)) add(server, server.resolve(world + "_the_end"), zip, Collections.<String>emptySet());
                if (inc.getBoolean("player-data", true)) for (String p : player) add(server, server.resolve(world).resolve(p), zip, Collections.<String>emptySet());
                if (inc.getBoolean("plugin-data", true)) {
                    Path plugins = server.resolve("plugins");
                    if (Files.isDirectory(plugins)) try (DirectoryStream<Path> ds = Files.newDirectoryStream(plugins)) {
                        for (Path p : ds) if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && !p.getFileName().toString().equalsIgnoreCase("LuoBackup")) add(server, p, zip, Collections.<String>emptySet());
                    }
                }
            }
            long size = Files.size(temp), total = size;
            for (Path p : old) total = Math.addExact(total, Files.size(p));
            if (limit > 0 && size > limit) throw new IOException("New archive exceeds size limit");
            if (policy.equals("stop") && (old.size() + 1 > slots || (limit > 0 && total > limit))) throw new IOException("Storage limit reached");
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
            Path finished = out.resolve(stamp + "-" + id + ".zip");
            Files.move(temp, finished);
            while (!old.isEmpty() && (old.size() + 1 > slots || (limit > 0 && total > limit))) {
                Path first = old.remove(0); long bytes = Files.size(first); Files.delete(first); total -= bytes;
            }
            getLogger().info("Backup complete: " + finished + " (" + size + " bytes)");
        } finally { Files.deleteIfExists(temp); }
    }
    private List<Path> archives(Path dir) throws IOException {
        List<Path> list = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.zip")) { for (Path p : ds) if (p.getFileName().toString().matches("\\d{8}-\\d{6}-[0-9a-f-]{36}\\.zip") && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) list.add(p); }
        list.sort(Comparator.comparing(p -> p.getFileName().toString())); return list;
    }
    private void add(Path base, Path path, ZipOutputStream zip, Set<String> excluded) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { getLogger().warning("Missing backup path: " + path); return; }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                if (Files.isSymbolicLink(dir) || (!dir.equals(path) && dir.getParent().equals(path) && excluded.contains(dir.getFileName().toString()))) return FileVisitResult.SKIP_SUBTREE;
                zip.putNextEntry(new ZipEntry(base.relativize(dir).toString().replace('\\', '/') + "/")); zip.closeEntry(); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                if (!a.isRegularFile() || Files.isSymbolicLink(file) || file.getFileName().toString().equals("session.lock")) return FileVisitResult.CONTINUE;
                zip.putNextEntry(new ZipEntry(base.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, zip); zip.closeEntry(); return FileVisitResult.CONTINUE;
            }
        });
    }
}
