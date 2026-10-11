package com.queuesense;

import java.time.*;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Seeds venues and 30 days of synthetic traffic (lunch/dinner rushes, quiet weekends, library curves). */
@Component
public class DataSeeder implements ApplicationRunner {
    private static final Object[][] VENUES = {{"Main Canteen", "canteen", 80, 30}, {"Hostel Mess", "mess", 150, 45}, {"Central Library", "library", 200, 180}};
    private static final Map<String, int[][]> PROFILES = Map.of(  // {hour, arrivals at weekday peak}
            "canteen", new int[][]{{8, 15}, {9, 20}, {10, 12}, {11, 25}, {12, 70}, {13, 85}, {14, 40}, {15, 20}, {16, 30}, {17, 45}, {18, 25}, {19, 12}},
            "mess", new int[][]{{7, 40}, {8, 75}, {9, 45}, {12, 80}, {13, 110}, {14, 50}, {19, 70}, {20, 120}, {21, 60}},
            "library", new int[][]{{9, 20}, {10, 30}, {11, 30}, {12, 15}, {13, 15}, {14, 30}, {15, 45}, {16, 50}, {17, 40}, {18, 25}, {19, 30}, {20, 40}, {21, 30}, {22, 10}});
    private static final Map<String, Integer> MEAN_MIN = Map.of("canteen", 18, "mess", 25, "library", 120);
    private final JdbcTemplate jdbc;
    private final Random rnd = new Random();

    public DataSeeder(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM venues", Integer.class) > 0) return;
        ZoneId zone = ZoneId.systemDefault();
        long now = System.currentTimeMillis();
        List<Object[]> rows = new ArrayList<>();
        for (Object[] v : VENUES) {
            String type = (String) v[1];
            int exp = (Integer) v[3];
            jdbc.update("INSERT INTO venues (name, type, capacity, expiry_minutes) VALUES (?,?,?,?)", v);
            long vid = jdbc.queryForObject("SELECT id FROM venues WHERE name=?", Long.class, v[0]);
            for (int d = 30; d >= 0; d--) {
                LocalDate date = LocalDate.now().minusDays(d);
                long dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli();
                boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
                double mult = weekend ? (type.equals("library") ? 0.8 : 0.55) : 1.0;
                for (int[] hb : PROFILES.get(type)) {
                    double rate = hb[1] * mult * (0.8 + rnd.nextDouble() * 0.4);
                    long count = Math.max(0, Math.round(rate + Math.sqrt(rate) * rnd.nextGaussian()));
                    for (long i = 0; i < count; i++) {
                        long tIn = dayStart + (hb[0] * 3600L + rnd.nextInt(3600)) * 1000;
                        if (tIn > now) continue;
                        long dur; String reason;
                        if (rnd.nextDouble() < 0.1) { dur = exp * 60L; reason = "auto_expired"; }  // forgot to tap out
                        else {
                            int mean = MEAN_MIN.get(type);
                            dur = Math.min(Math.max(120, Math.round((mean + mean * 0.3 * rnd.nextGaussian()) * 60)), exp * 60L);
                            reason = "manual";
                        }
                        String dev = "seed-" + rnd.nextLong();
                        long tOut = tIn + dur * 1000;
                        rows.add(tOut > now ? new Object[]{vid, dev, tIn, null, null, null}   // still inside right now
                                            : new Object[]{vid, dev, tIn, tOut, reason, dur});
                    }
                }
            }
        }
        jdbc.batchUpdate("INSERT INTO check_ins (venue_id, device_hash, checked_in_at, checked_out_at, close_reason, duration_sec) VALUES (?,?,?,?,?,?)", rows);
        System.out.println("Seeded " + VENUES.length + " venues, " + rows.size() + " check-ins");
    }
}
