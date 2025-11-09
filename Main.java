import java.awt.Desktop;
import java.io.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.security.SecureRandom;

public class Main {
    private static final long DEFAULT_TTL_SECONDS = 24 * 3600;
    private static final int SHORT_CODE_LEN = 6;

    private static final String UUID_FILE = "user.uuid";


    private static final Map<String, LinkRecord> codeToRecord = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> userToCodes = new ConcurrentHashMap<>();
    private static final Map<String, Queue<String>> notifications = new ConcurrentHashMap<>();


    private static ScheduledExecutorService scheduler;

    public static void main(String[] args) throws Exception {
        String userUUID = loadOrCreateUserUUID();
        System.out.println("Ваш user UUID: " + userUUID + " (сохранён в " + UUID_FILE + ")");
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(Main::cleanupExpired, 60, 60, TimeUnit.SECONDS);

        Scanner sc = new Scanner(System.in);
        printHelp();
        while (true) {
            System.out.print("\n> ");
            String cmd = sc.nextLine().trim();
            if (cmd.isEmpty()) continue;
            String[] parts = cmd.split("\\s+", 2);
            String c = parts[0].toLowerCase(Locale.ROOT);
            try {
                switch (c) {
                    case "help":
                        printHelp();
                        break;
                    case "create":
                        if (parts.length < 2) {
                            System.out.println("Usage: create <longUrl> [maxClicks]");
                            break;
                        }
                        String[] p = parts[1].split("\\s+", 2);
                        String longUrl = p[0];
                        long maxClicks = 0;
                        if (p.length > 1) {
                            try { maxClicks = Long.parseLong(p[1]); }
                            catch (Exception e) { maxClicks = 0; }
                        }
                        LinkRecord rec = createShortLink(longUrl, userUUID, maxClicks, DEFAULT_TTL_SECONDS);
                        System.out.println("Short code: " + rec.code);
                        System.out.println("Short URL: http://localhost/" + rec.code);
                        break;
                    case "list":
                        listUserLinks(userUUID);
                        break;
                    case "open":
                        if (parts.length < 2) {
                            System.out.println("Usage: open <code-or-full-url>");
                            break;
                        }
                        String arg = parts[1].trim();
                        openShortLink(arg, userUUID);
                        break;
                    case "notes":
                        showNotifications(userUUID);
                        break;
                    case "exit":
                    case "quit":
                        shutdown();
                        System.out.println("Bye.");
                        return;
                    default:
                        System.out.println("Неизвестная команда. Напишите help.");
                }
            } catch (Exception ex) {
                System.out.println("Ошибка: " + ex.getMessage());
                ex.printStackTrace(System.out);
            }
        }
    }

    private static void printHelp() {
        System.out.println("Команды:");
        System.out.println("  create <longUrl> [maxClicks]  - создать короткую ссылку (maxClicks=0 => нет лимита)");
        System.out.println("  list                          - показать ваши ссылки и статистику");
        System.out.println("  open <code-or-full-url>       - открыть короткую ссылку (увеличивает счётчик)");
        System.out.println("  notes                         - показать уведомления и очистить их");
        System.out.println("  help                          - показать это сообщение");
        System.out.println("  exit                          - выйти");
        System.out.println("\nПример: create https://www.example.com 5");
    }

    // ---------- файл UUID ----------
    private static String loadOrCreateUserUUID() {
        File f = new File(UUID_FILE);
        try {
            if (f.exists()) {
                try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                    String s = br.readLine();
                    if (s != null && !s.isBlank()) return s.trim();
                }
            }
            String uuid = UUID.randomUUID().toString();
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(f))) {
                bw.write(uuid);
            }
            return uuid;
        } catch (IOException e) {
            return UUID.randomUUID().toString();
        }
    }

    // ---------- создание короткой ссылки ----------
    private static synchronized LinkRecord createShortLink(String longUrl, String userUUID, long maxClicks, long ttlSeconds) {
        userToCodes.putIfAbsent(userUUID, ConcurrentHashMap.newKeySet());
        for (String code : userToCodes.get(userUUID)) {
            LinkRecord r = codeToRecord.get(code);
            if (r != null && r.longUrl.equals(longUrl) && r.isActive()) {
                return r;
            }
        }
        String code;
        do {
            code = ShortCodeGenerator.generate(SHORT_CODE_LEN);
        } while (codeToRecord.containsKey(code));
        LinkRecord rec = new LinkRecord(code, longUrl, userUUID, Instant.now(), ttlSeconds, maxClicks);
        codeToRecord.put(code, rec);
        userToCodes.get(userUUID).add(code);
        return rec;
    }

    // ---------- открытие / переход (редирект) ----------
    private static void openShortLink(String arg, String requesterUUID) {
        String toOpen;
        String code = null;
        if (arg.startsWith("http://") || arg.startsWith("https://")) {
            toOpen = arg;
            openInBrowser(toOpen);
            return;
        } else {
            code = arg;
            LinkRecord r = codeToRecord.get(code);
            if (r == null) {
                System.out.println("Код не найден.");
                return;
            }
            synchronized (r) {
                if (!r.isActive()) {
                    System.out.println("Ссылка неактивна (исчерпан лимит или истёк TTL).");
                    return;
                }
                boolean ok = r.incrementClick();
                if (!ok) {
                    System.out.println("Ссылка стала неактивна (лимит достигнут).");
                    return;
                }
                if (!r.isActive()) {
                    addNotification(r.ownerUUID, "Ссылка " + r.code + " исчерпала лимит переходов.");
                }
            }
            toOpen = r.longUrl;
            openInBrowser(toOpen);
            System.out.println("Открываю: " + toOpen + " (code=" + code + ")");
        }
    }

    private static void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                System.out.println("Desktop не поддерживается. Откройте вручную: " + url);
            }
        } catch (Exception e) {
            System.out.println("Не удалось открыть браузер: " + e.getMessage());
        }
    }

    // ---------- list ----------
    private static void listUserLinks(String userUUID) {
        Set<String> set = userToCodes.getOrDefault(userUUID, Collections.emptySet());
        if (set.isEmpty()) {
            System.out.println("У вас нет ссылок.");
            return;
        }
        System.out.println("Ваши ссылки:");
        for (String code : set) {
            LinkRecord r = codeToRecord.get(code);
            if (r == null) continue;
            System.out.println(String.format(" code=%s  clicks=%d/%s  active=%s  url=%s  created=%s",
                    r.code,
                    r.getClicks(),
                    (r.maxClicks==0 ? "∞" : r.maxClicks),
                    r.isActive(),
                    r.longUrl,
                    r.createdAt
            ));
        }
    }

    // ---------- notifications ----------
    private static void addNotification(String userUUID, String text) {
        notifications.putIfAbsent(userUUID, new ConcurrentLinkedQueue<>());
        notifications.get(userUUID).add(Instant.now() + " - " + text);
    }
    private static void showNotifications(String userUUID) {
        Queue<String> q = notifications.getOrDefault(userUUID, new ConcurrentLinkedQueue<>());
        if (q.isEmpty()) {
            System.out.println("Уведомлений нет.");
            return;
        }
        System.out.println("Уведомления:");
        String s;
        while ((s = q.poll()) != null) {
            System.out.println("  " + s);
        }
    }

    // ---------- cleanup ----------
    private static void cleanupExpired() {
        Instant now = Instant.now();
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, LinkRecord> e : codeToRecord.entrySet()) {
            LinkRecord r = e.getValue();
            if (r.isExpiredByTime(now)) {
                toRemove.add(e.getKey());
            }
        }
        for (String code : toRemove) {
            LinkRecord r = codeToRecord.remove(code);
            if (r != null) {
                Set<String> set = userToCodes.get(r.ownerUUID);
                if (set != null) set.remove(code);
                addNotification(r.ownerUUID, "Ссылка " + r.code + " удалена по истечению TTL.");
            }
        }
    }

    private static void shutdown() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    // ---------- вспомогательные классы ----------
    private static class LinkRecord {
        final String code;
        final String longUrl;
        final String ownerUUID;
        final Instant createdAt;
        final long ttlSeconds;
        volatile long clicks;
        final long maxClicks; 

        LinkRecord(String code, String longUrl, String ownerUUID, Instant createdAt, long ttlSeconds, long maxClicks) {
            this.code = code;
            this.longUrl = longUrl;
            this.ownerUUID = ownerUUID;
            this.createdAt = createdAt;
            this.ttlSeconds = ttlSeconds;
            this.clicks = 0;
            this.maxClicks = maxClicks;
        }

        synchronized boolean incrementClick() {
            if (!isActive()) return false;
            clicks++;
            return true;
        }

        synchronized long getClicks() { return clicks; }

        synchronized boolean isActive() {
            if (maxClicks > 0 && clicks >= maxClicks) return false;
            if (Instant.now().isAfter(createdAt.plusSeconds(ttlSeconds))) return false;
            return true;
        }

        boolean isExpiredByTime(Instant now) {
            return now.isAfter(createdAt.plusSeconds(ttlSeconds));
        }
    }

    private static class ShortCodeGenerator {
        private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        private static final Random rnd = new SecureRandom();
        static String generate(int n) {
            StringBuilder sb = new StringBuilder(n);
            for (int i = 0; i < n; i++) sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
            return sb.toString();
        }
    }
}
