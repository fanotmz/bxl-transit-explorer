package be.bxltransit.explorer.imports;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GtfsImportService {

    private final JdbcTemplate jdbc;

    public GtfsImportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public int importLignes(Path dossier) throws IOException {
        List<Object[]> lignes = new ArrayList<>();
        for (CSVRecord r : lire(dossier.resolve("routes.txt"))) {
            lignes.add(new Object[] {
                r.get("route_id"),
                r.get("route_short_name"),
                vide(r.get("route_long_name")),
                mode(r.get("route_type")),
                vide(r.get("route_color")),
                vide(r.get("route_text_color"))
            });
        }
        jdbc.batchUpdate("""
            INSERT INTO ligne (route_id, numero, nom, mode, couleur, couleur_texte)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (route_id) DO UPDATE SET
                numero = EXCLUDED.numero, nom = EXCLUDED.nom, mode = EXCLUDED.mode,
                couleur = EXCLUDED.couleur, couleur_texte = EXCLUDED.couleur_texte
            """, lignes);
        return lignes.size();
    }

    @Transactional
    public int importArrets(Path dossier) throws IOException {
        List<Object[]> arrets = new ArrayList<>();
        for (CSVRecord r : lire(dossier.resolve("stops.txt"))) {
            String type = r.get("location_type");
            if (!type.isBlank() && !type.equals("0")) {
                continue; // on garde uniquement les arrets (pas les stations ni les acces)
            }
            arrets.add(new Object[] {
                r.get("stop_id"),
                r.get("stop_name"),
                Double.parseDouble(r.get("stop_lat")),
                Double.parseDouble(r.get("stop_lon")),
                pmr(r.get("wheelchair_boarding"))
            });
        }
        jdbc.batchUpdate("""
            INSERT INTO arret (stop_id, nom, latitude, longitude, accessible_pmr)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (stop_id) DO UPDATE SET
                nom = EXCLUDED.nom, latitude = EXCLUDED.latitude,
                longitude = EXCLUDED.longitude, accessible_pmr = EXCLUDED.accessible_pmr
            """, arrets);
        return arrets.size();
    }

    private static final String[] NOMS_JOURS = {
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
    };

    private record ServiceJour(String serviceId, LocalDate date) {
    }

    @Transactional
    public int importServiceJours(Path dossier) throws IOException {
        DateTimeFormatter format = DateTimeFormatter.BASIC_ISO_DATE;
        Set<ServiceJour> jours = new LinkedHashSet<>();
        for (CSVRecord r : lire(dossier.resolve("calendar.txt"))) {
            LocalDate debut = LocalDate.parse(r.get("start_date"), format);
            LocalDate fin = LocalDate.parse(r.get("end_date"), format);
            for (LocalDate jour = debut; !jour.isAfter(fin); jour = jour.plusDays(1)) {
                String colonne = NOMS_JOURS[jour.getDayOfWeek().getValue() - 1];
                if (r.get(colonne).equals("1")) {
                    jours.add(new ServiceJour(r.get("service_id"), jour));
                }
            }
        }
        for (CSVRecord r : lire(dossier.resolve("calendar_dates.txt"))) {
            ServiceJour cle = new ServiceJour(r.get("service_id"), LocalDate.parse(r.get("date"), format));
            if (r.get("exception_type").equals("2")) {
                jours.remove(cle);
            } else {
                jours.add(cle);
            }
        }
        jdbc.update("DELETE FROM service_jour");
        List<Object[]> lignes = new ArrayList<>();
        for (ServiceJour j : jours) {
            lignes.add(new Object[] { j.serviceId(), java.sql.Date.valueOf(j.date()) });
        }
        jdbc.batchUpdate("INSERT INTO service_jour (service_id, date) VALUES (?, ?)", lignes);
        return lignes.size();
    }

    private static final double TOLERANCE_M = 5.0;
    private static final double RAYON_TERRE_M = 6371008.8;

    @Transactional
    public int importTraces(Path dossier) throws IOException {
        Map<String, List<double[]>> parTrace = new LinkedHashMap<>();
        for (CSVRecord r : lire(dossier.resolve("shapes.txt"))) {
            parTrace.computeIfAbsent(r.get("shape_id"), k -> new ArrayList<>())
                    .add(new double[] {
                        Double.parseDouble(r.get("shape_pt_lat")),
                        Double.parseDouble(r.get("shape_pt_lon")),
                        Double.parseDouble(r.get("shape_pt_sequence"))
                    });
        }
        double kx = 111320 * Math.cos(Math.toRadians(50.85));
        double ky = 110574;
        List<Object[]> lignes = new ArrayList<>();
        for (Map.Entry<String, List<double[]>> e : parTrace.entrySet()) {
            List<double[]> pts = e.getValue();
            pts.sort(Comparator.comparingDouble(p -> p[2]));
            int n = pts.size();
            double[] x = new double[n];
            double[] y = new double[n];
            double[] cum = new double[n];
            for (int i = 0; i < n; i++) {
                double[] p = pts.get(i);
                x[i] = p[1] * kx;
                y[i] = p[0] * ky;
                if (i > 0) {
                    double[] q = pts.get(i - 1);
                    cum[i] = cum[i - 1] + haversine(q[0], q[1], p[0], p[1]);
                }
            }
            boolean[] garde = simplifier(x, y, TOLERANCE_M);
            StringBuilder json = new StringBuilder("[");
            boolean premier = true;
            for (int i = 0; i < n; i++) {
                if (!garde[i]) {
                    continue;
                }
                if (!premier) {
                    json.append(',');
                }
                premier = false;
                json.append(String.format(Locale.ROOT, "[%.6f,%.6f,%d]",
                        pts.get(i)[0], pts.get(i)[1], Math.round(cum[i])));
            }
            json.append(']');
            lignes.add(new Object[] { e.getKey(), json.toString(), (int) Math.round(cum[n - 1]) });
        }
        jdbc.batchUpdate("""
            INSERT INTO trace (shape_id, points, longueur_m)
            VALUES (?, ?::jsonb, ?)
            ON CONFLICT (shape_id) DO UPDATE SET
                points = EXCLUDED.points, longueur_m = EXCLUDED.longueur_m
            """, lignes);
        return lignes.size();
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * RAYON_TERRE_M * Math.asin(Math.sqrt(h));
    }

    private static boolean[] simplifier(double[] x, double[] y, double tolerance) {
        int n = x.length;
        boolean[] garde = new boolean[n];
        garde[0] = true;
        garde[n - 1] = true;
        Deque<int[]> pile = new ArrayDeque<>();
        pile.push(new int[] { 0, n - 1 });
        while (!pile.isEmpty()) {
            int[] seg = pile.pop();
            int a = seg[0];
            int b = seg[1];
            if (b <= a + 1) {
                continue;
            }
            double dx = x[b] - x[a];
            double dy = y[b] - y[a];
            double l2 = dx * dx + dy * dy;
            double max = -1;
            int idx = -1;
            for (int i = a + 1; i < b; i++) {
                double d;
                if (l2 == 0) {
                    d = Math.hypot(x[i] - x[a], y[i] - y[a]);
                } else {
                    double t = Math.max(0, Math.min(1, ((x[i] - x[a]) * dx + (y[i] - y[a]) * dy) / l2));
                    d = Math.hypot(x[i] - (x[a] + t * dx), y[i] - (y[a] + t * dy));
                }
                if (d > max) {
                    max = d;
                    idx = i;
                }
            }
            if (max > tolerance) {
                garde[idx] = true;
                pile.push(new int[] { a, idx });
                pile.push(new int[] { idx, b });
            }
        }
        return garde;
    }

    private record Trajet(String routeId, String serviceId, int sens, String destination,
                          String shapeId, Boolean pmr) {
    }

    @Transactional
    public int[] importHoraires(Path dossier) throws IOException {
        Map<String, Trajet> trajets = new HashMap<>();
        try (CSVParser parser = ouvrir(dossier.resolve("trips.txt"))) {
            for (CSVRecord r : parser) {
                trajets.put(r.get("trip_id"), new Trajet(
                        r.get("route_id"), r.get("service_id"),
                        Integer.parseInt(r.get("direction_id")), vide(r.get("trip_headsign")),
                        r.get("shape_id"), pmr(r.get("wheelchair_accessible"))));
            }
        }

        jdbc.update("DELETE FROM course");
        jdbc.update("DELETE FROM patron_arret");
        jdbc.update("DELETE FROM patron");

        Map<String, Long> patrons = new HashMap<>();
        List<Object[]> lignesPatron = new ArrayList<>();
        List<Object[]> lignesArret = new ArrayList<>();
        List<Object[]> lignesCourse = new ArrayList<>();

        String courant = null;
        List<String> arrets = new ArrayList<>();
        List<Integer> heures = new ArrayList<>();
        try (CSVParser parser = ouvrir(dossier.resolve("stop_times.txt"))) {
            for (CSVRecord r : parser) {
                String id = r.get("trip_id");
                if (courant != null && !id.equals(courant)) {
                    enregistrer(courant, trajets.get(courant), arrets, heures,
                            patrons, lignesPatron, lignesArret, lignesCourse);
                    arrets.clear();
                    heures.clear();
                }
                courant = id;
                arrets.add(r.get("stop_id"));
                heures.add(secondes(r.get("arrival_time")));
            }
            if (courant != null) {
                enregistrer(courant, trajets.get(courant), arrets, heures,
                        patrons, lignesPatron, lignesArret, lignesCourse);
            }
        }

        inserer("INSERT INTO patron (patron_id, route_id, sens, destination, shape_id, duree_s) "
                + "VALUES (?, ?, ?, ?, ?, ?)", lignesPatron);
        inserer("INSERT INTO patron_arret (patron_id, ordre, stop_id, decalage_s) "
                + "VALUES (?, ?, ?, ?)", lignesArret);
        inserer("INSERT INTO course (course_id, patron_id, service_id, heure_depart_s, accessible_pmr) "
                + "VALUES (?, ?, ?, ?, ?)", lignesCourse);
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('patron', 'patron_id'), ?)",
                Long.class, (long) lignesPatron.size());
        return new int[] { lignesPatron.size(), lignesArret.size(), lignesCourse.size() };
    }

    private static void enregistrer(String tripId, Trajet t, List<String> arrets, List<Integer> heures,
                                    Map<String, Long> patrons, List<Object[]> lignesPatron,
                                    List<Object[]> lignesArret, List<Object[]> lignesCourse) {
        if (t == null) {
            throw new IllegalStateException("Course inconnue dans trips.txt : " + tripId);
        }
        int depart = heures.get(0);
        StringBuilder cle = new StringBuilder();
        cle.append(t.routeId()).append('|').append(t.sens()).append('|').append(t.shapeId());
        for (int i = 0; i < arrets.size(); i++) {
            cle.append('|').append(arrets.get(i)).append(',').append(heures.get(i) - depart);
        }
        String k = cle.toString();
        Long patronId = patrons.get(k);
        if (patronId == null) {
            patronId = (long) patrons.size() + 1;
            patrons.put(k, patronId);
            int duree = heures.get(heures.size() - 1) - depart;
            lignesPatron.add(new Object[] {
                patronId, t.routeId(), t.sens(), t.destination(), t.shapeId(), duree });
            for (int i = 0; i < arrets.size(); i++) {
                lignesArret.add(new Object[] {
                    patronId, i + 1, arrets.get(i), heures.get(i) - depart });
            }
        }
        lignesCourse.add(new Object[] { tripId, patronId, t.serviceId(), depart, t.pmr() });
    }

    private void inserer(String sql, List<Object[]> lignes) {
        final int taille = 5000;
        for (int debut = 0; debut < lignes.size(); debut += taille) {
            jdbc.batchUpdate(sql, lignes.subList(debut, Math.min(debut + taille, lignes.size())));
        }
    }

    private static int secondes(String heure) {
        String[] p = heure.split(":");
        return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]);
    }

    private static CSVParser ouvrir(Path fichier) throws IOException {
        Reader reader = Files.newBufferedReader(fichier, StandardCharsets.UTF_8);
        reader.mark(1);
        if (reader.read() != 0xFEFF) {
            reader.reset();
        }
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();
        return format.parse(reader);
    }

    private static List<CSVRecord> lire(Path fichier) throws IOException {
        try (Reader reader = Files.newBufferedReader(fichier, StandardCharsets.UTF_8)) {
            reader.mark(1);
            if (reader.read() != 0xFEFF) {
                reader.reset();
            }
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .build();
            try (CSVParser parser = format.parse(reader)) {
                return parser.getRecords();
            }
        }
    }

    private static String vide(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur;
    }

    private static String mode(String type) {
        return switch (type) {
            case "0" -> "tram";
            case "1" -> "metro";
            case "3" -> "bus";
            default -> throw new IllegalArgumentException("Type de ligne inconnu : " + type);
        };
    }

    private static Boolean pmr(String valeur) {
        return switch (valeur) {
            case "1" -> Boolean.TRUE;
            case "2" -> Boolean.FALSE;
            default -> null;
        };
    }
}
