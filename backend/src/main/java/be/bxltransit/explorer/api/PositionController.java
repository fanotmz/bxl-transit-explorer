package be.bxltransit.explorer.api;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class PositionController {

    private static final ZoneId BRUXELLES = ZoneId.of("Europe/Brussels");
    private static final Pattern NOMBRE = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private final JdbcTemplate jdbc;

    public PositionController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PositionDto(String courseId, int sens, String destination,
                              double latitude, double longitude) {
    }

    private record CourseActive(String courseId, long patronId, int sens, String destination,
                                String shapeId, int ecoule) {
    }

    @GetMapping("/lignes/{routeId}/positions")
    public List<PositionDto> positions(@PathVariable String routeId,
                                       @RequestParam(required = false) String date,
                                       @RequestParam(required = false) String heure) {
        Integer nb = jdbc.queryForObject("SELECT count(*) FROM ligne WHERE route_id = ?",
                Integer.class, routeId);
        if (nb == null || nb == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ligne inconnue : " + routeId);
        }

        ZonedDateTime maintenant = ZonedDateTime.now(BRUXELLES);
        LocalDate jour;
        LocalTime moment;
        try {
            jour = date == null ? maintenant.toLocalDate() : LocalDate.parse(date);
            moment = heure == null ? maintenant.toLocalTime() : LocalTime.parse(heure);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "date (aaaa-mm-jj) ou heure (hh:mm) invalide");
        }
        int t = moment.toSecondOfDay();

        List<CourseActive> actives = jdbc.query("""
            SELECT c.course_id, c.patron_id, p.sens, p.destination, p.shape_id,
                   (? + j.decalage) - c.heure_depart_s AS ecoule
            FROM (
                SELECT service_id, 0 AS decalage FROM service_jour WHERE date = ?
                UNION ALL
                SELECT service_id, 86400 AS decalage FROM service_jour WHERE date = ?
            ) j
            JOIN course c ON c.service_id = j.service_id
            JOIN patron p ON p.patron_id = c.patron_id
            WHERE p.route_id = ?
              AND (? + j.decalage) BETWEEN c.heure_depart_s AND c.heure_depart_s + p.duree_s
            ORDER BY c.course_id
            """, (rs, i) -> new CourseActive(rs.getString("course_id"), rs.getLong("patron_id"),
                rs.getInt("sens"), rs.getString("destination"), rs.getString("shape_id"),
                rs.getInt("ecoule")),
            t, Date.valueOf(jour), Date.valueOf(jour.minusDays(1)), routeId, t);
        if (actives.isEmpty()) {
            return List.of();
        }

        Set<Long> patronIds = new LinkedHashSet<>();
        Set<String> shapeIds = new LinkedHashSet<>();
        for (CourseActive c : actives) {
            patronIds.add(c.patronId());
            shapeIds.add(c.shapeId());
        }

        Map<Long, List<int[]>> horaires = new HashMap<>();
        jdbc.query("SELECT patron_id, decalage_s, distance_trace_m FROM patron_arret WHERE patron_id IN ("
                + marques(patronIds.size()) + ") ORDER BY patron_id, ordre",
                (RowCallbackHandler) rs -> {
                    horaires.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(new int[] { rs.getInt(2), rs.getInt(3) });
                }, patronIds.toArray());

        Map<String, double[][]> traces = new HashMap<>();
        jdbc.query("SELECT shape_id, points::text FROM trace WHERE shape_id IN ("
                + marques(shapeIds.size()) + ")",
                (RowCallbackHandler) rs -> {
                    traces.put(rs.getString(1), lireTrace(rs.getString(2)));
                }, shapeIds.toArray());

        List<PositionDto> resultat = new ArrayList<>();
        for (CourseActive c : actives) {
            double distance = distanceAtteinte(horaires.get(c.patronId()), c.ecoule());
            double[] point = pointSurTrace(traces.get(c.shapeId()), distance);
            resultat.add(new PositionDto(c.courseId(), c.sens(), c.destination(), point[0], point[1]));
        }
        return resultat;
    }

    private static String marques(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    private static double distanceAtteinte(List<int[]> horaire, int ecoule) {
        for (int k = 0; k < horaire.size() - 1; k++) {
            int[] a = horaire.get(k);
            int[] b = horaire.get(k + 1);
            if (ecoule <= b[0]) {
                double f = b[0] == a[0] ? 1 : Math.max(0, (double) (ecoule - a[0]) / (b[0] - a[0]));
                return a[1] + f * (b[1] - a[1]);
            }
        }
        return horaire.get(horaire.size() - 1)[1];
    }

    private static double[] pointSurTrace(double[][] trace, double distance) {
        for (int i = 0; i < trace.length - 1; i++) {
            if (distance <= trace[i + 1][2]) {
                double longueur = trace[i + 1][2] - trace[i][2];
                double f = longueur <= 0 ? 0 : Math.max(0, (distance - trace[i][2]) / longueur);
                return new double[] {
                    trace[i][0] + f * (trace[i + 1][0] - trace[i][0]),
                    trace[i][1] + f * (trace[i + 1][1] - trace[i][1])
                };
            }
        }
        double[] dernier = trace[trace.length - 1];
        return new double[] { dernier[0], dernier[1] };
    }

    private static double[][] lireTrace(String json) {
        List<Double> valeurs = new ArrayList<>();
        Matcher m = NOMBRE.matcher(json);
        while (m.find()) {
            valeurs.add(Double.parseDouble(m.group()));
        }
        int n = valeurs.size() / 3;
        double[][] points = new double[n][3];
        for (int i = 0; i < n; i++) {
            points[i][0] = valeurs.get(3 * i);
            points[i][1] = valeurs.get(3 * i + 1);
            points[i][2] = valeurs.get(3 * i + 2);
        }
        return points;
    }
}
