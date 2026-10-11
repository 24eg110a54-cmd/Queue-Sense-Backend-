package com.queuesense;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class QueueService {
    static final double LOW = 0.40, HIGH = 0.75;  // crowd level thresholds (fraction of capacity)
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZONE);
    private final JdbcTemplate jdbc;

    public QueueService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static double round1(double x) { return Math.round(x * 10) / 10.0; }
    static long lng(Map<String, Object> m, String k) { return ((Number) m.get(k)).longValue(); }
    static String level(double pct) { return pct < LOW ? "low" : pct < HIGH ? "medium" : "high"; }
    static ZonedDateTime at(long ms) { return Instant.ofEpochMilli(ms).atZone(ZONE); }

    private void closeRow(long id, long in, long when, String reason) {
        jdbc.update("UPDATE check_ins SET checked_out_at=?, close_reason=?, duration_sec=? WHERE id=?",
                when, reason, Math.round((when - in) / 1000.0), id);
    }

    /** Auto-expiry: close check-ins once the planned stay (or the venue's default window) has passed. */
    @Scheduled(fixedRate = 60_000)
    public void expire() {
        long now = System.currentTimeMillis();
        var rows = jdbc.queryForList("SELECT c.id, c.checked_in_at, c.planned_minutes, v.expiry_minutes FROM check_ins c "
                + "JOIN venues v ON v.id = c.venue_id WHERE c.checked_out_at IS NULL");
        for (var c : rows) {
            long in = lng(c, "checked_in_at");
            long minutes = c.get("planned_minutes") != null ? lng(c, "planned_minutes") : lng(c, "expiry_minutes");
            long end = in + minutes * 60_000;
            if (end < now) closeRow(lng(c, "id"), in, end, "auto_expired");
        }
    }

    public Map<String, Object> checkIn(Long venueId, String deviceHash, String name, Double hours) {
        if (venueId == null || jdbc.queryForObject("SELECT COUNT(*) FROM venues WHERE id=?", Integer.class, venueId) == 0)
            throw new ApiException(404, "Venue not found");
        if (deviceHash == null || deviceHash.isBlank()) throw new ApiException(422, "device_hash is required");
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 40) throw new ApiException(422, "Enter your name (up to 40 characters)");
        if (hours == null || hours < 0.25 || hours > 12)
            throw new ApiException(422, "Stay length must be between 15 minutes and 12 hours");
        long now = System.currentTimeMillis();
        for (var c : jdbc.queryForList("SELECT id, venue_id, checked_in_at FROM check_ins WHERE device_hash=? AND checked_out_at IS NULL", deviceHash)) {
            if (lng(c, "venue_id") == venueId) throw new ApiException(409, "Already checked in here");
            closeRow(lng(c, "id"), lng(c, "checked_in_at"), now, "switched");  // one open check-in per device
        }
        jdbc.update("INSERT INTO check_ins (venue_id, device_hash, checked_in_at, name, planned_minutes) VALUES (?,?,?,?,?)",
                venueId, deviceHash, now, n, Math.round(hours * 60));
        return Map.of("ok", true);
    }

    public Map<String, Object> checkOut(Long venueId, String deviceHash) {
        var rows = jdbc.queryForList("SELECT id, checked_in_at FROM check_ins WHERE venue_id=? AND device_hash=? AND checked_out_at IS NULL",
                venueId, deviceHash);
        if (rows.isEmpty()) throw new ApiException(404, "No open check-in for this venue");
        long now = System.currentTimeMillis(), in = lng(rows.get(0), "checked_in_at");
        closeRow(lng(rows.get(0), "id"), in, now, "manual");
        return Map.of("ok", true, "duration_sec", Math.round((now - in) / 1000.0));
    }

    private int arrivals(long vid, long now, int fromMin, int toMin) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM check_ins WHERE venue_id=? AND checked_in_at>=? AND checked_in_at<?",
                Integer.class, vid, now - toMin * 60_000L, now - fromMin * 60_000L + 1);
    }

    public List<Map<String, Object>> live(String deviceHash) {
        long now = System.currentTimeMillis();
        var out = new ArrayList<Map<String, Object>>();
        for (var v : jdbc.queryForList("SELECT * FROM venues ORDER BY id")) {
            long vid = lng(v, "id");
            int cap = (int) lng(v, "capacity"), exp = (int) lng(v, "expiry_minutes");
            var open = jdbc.queryForList("SELECT * FROM check_ins WHERE venue_id=? AND checked_out_at IS NULL", vid);
            double weighted = 0;
            Map<String, Object> mine = null;
            for (var c : open) {  // exponential decay: older open check-ins count less (tau = venue expiry window)
                weighted += Math.exp(-((now - lng(c, "checked_in_at")) / 60_000.0) / exp);
                if (deviceHash != null && deviceHash.equals(c.get("device_hash"))) mine = c;
            }
            double pct = weighted / cap;
            int recent = arrivals(vid, now, 0, 15), prev = arrivals(vid, now, 15, 30);
            var m = new LinkedHashMap<String, Object>();
            m.put("id", vid); m.put("name", v.get("name")); m.put("type", v.get("type")); m.put("capacity", cap);
            m.put("count", open.size()); m.put("weighted", round1(weighted));
            m.put("percent", (int) Math.floor(Math.min(pct, 1.5) * 100)); m.put("level", level(pct));
            m.put("trend", recent > prev + 2 ? "rising" : recent < prev - 2 ? "falling" : "steady");
            m.put("checked_in", mine != null);
            m.put("my_name", mine != null ? mine.get("name") : null);
            String until = null;
            if (mine != null) {
                long mins = mine.get("planned_minutes") != null ? lng(mine, "planned_minutes") : exp;
                until = HHMM.format(Instant.ofEpochMilli(lng(mine, "checked_in_at") + mins * 60_000));
            }
            m.put("my_until", until);
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> today(long vid) {
        long start = LocalDate.now().atStartOfDay(ZONE).toInstant().toEpochMilli();
        int[] cnt = new int[24];
        for (long t : jdbc.queryForList("SELECT checked_in_at FROM check_ins WHERE venue_id=? AND checked_in_at>=?", Long.class, vid, start))
            cnt[at(t).getHour()]++;
        var out = new ArrayList<Map<String, Object>>();
        for (int h = 0; h < 24; h++) out.add(Map.of("hour", h, "arrivals", cnt[h]));
        return out;
    }

    /** Average arrivals for each (weekday, hour); Monday = 0. */
    public Map<String, Object> heatmap(long vid) {
        int[][] total = new int[7][24];
        List<Set<LocalDate>> dates = new ArrayList<>();
        for (int i = 0; i < 7; i++) dates.add(new HashSet<>());
        for (long t : jdbc.queryForList("SELECT checked_in_at FROM check_ins WHERE venue_id=?", Long.class, vid)) {
            ZonedDateTime z = at(t);
            int d = z.getDayOfWeek().getValue() - 1;
            total[d][z.getHour()]++;
            dates.get(d).add(z.toLocalDate());
        }
        var grid = new ArrayList<List<Double>>();
        for (int d = 0; d < 7; d++) {
            var row = new ArrayList<Double>();
            for (int h = 0; h < 24; h++) row.add(round1(total[d][h] / (double) Math.max(1, dates.get(d).size())));
            grid.add(row);
        }
        return Map.of("grid", grid);
    }

    public List<Map<String, Object>> compare() {
        var out = new ArrayList<Map<String, Object>>();
        for (var v : jdbc.queryForList("SELECT * FROM venues ORDER BY id")) {
            var rows = jdbc.queryForList("SELECT checked_in_at, duration_sec, close_reason FROM check_ins WHERE venue_id=?", lng(v, "id"));
            long totalDur = 0; int done = 0, expired = 0;
            Map<Integer, Integer> byHour = new HashMap<>();
            for (var r : rows) {
                if (r.get("duration_sec") != null && lng(r, "duration_sec") > 0) { done++; totalDur += lng(r, "duration_sec"); }
                if ("auto_expired".equals(r.get("close_reason"))) expired++;
                byHour.merge(at(lng(r, "checked_in_at")).getHour(), 1, Integer::sum);
            }
            var m = new LinkedHashMap<String, Object>();
            m.put("id", lng(v, "id")); m.put("name", v.get("name")); m.put("type", v.get("type"));
            m.put("total_visits", rows.size());
            m.put("avg_stay_min", round1(totalDur / (double) Math.max(1, done) / 60.0));
            m.put("auto_expired_pct", round1(100.0 * expired / Math.max(1, rows.size())));
            m.put("peak_hours", byHour.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(3).map(Map.Entry::getKey).toList());
            out.add(m);
        }
        return out;
    }
}
