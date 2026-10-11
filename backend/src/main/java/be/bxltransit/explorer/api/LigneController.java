package be.bxltransit.explorer.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class LigneController {

    private static final List<String> TYPES_JOUR = List.of("semaine", "samedi", "dimanche");

    private final JdbcTemplate jdbc;

    public LigneController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record LigneDto(String id, String numero, String nom, String mode,
                           String couleur, String couleurTexte) {
    }

    public record FrequenceDto(int sens, int heure, double passages) {
    }

    @GetMapping("/lignes")
    public List<LigneDto> lignes() {
        return jdbc.query("""
            SELECT route_id, numero, nom, mode, couleur, couleur_texte
            FROM ligne
            ORDER BY CASE mode WHEN 'metro' THEN 0 WHEN 'tram' THEN 1 ELSE 2 END,
                     CASE WHEN numero ~ '^[0-9]+$' THEN lpad(numero, 5, '0') ELSE numero END
            """, (rs, i) -> new LigneDto(
                rs.getString("route_id"), rs.getString("numero"), rs.getString("nom"),
                rs.getString("mode"), rs.getString("couleur"), rs.getString("couleur_texte")));
    }

    public record ArretDto(String id, String nom, double latitude, double longitude,
                           Boolean accessiblePmr) {
    }

    @GetMapping("/lignes/{routeId}/traces")
    public ResponseEntity<String> traces(@PathVariable String routeId) {
        verifierLigne(routeId);
        String json = jdbc.queryForObject("""
            SELECT COALESCE(jsonb_agg(jsonb_build_object(
                       'shapeId', t.shape_id, 'sens', s.sens, 'courses', s.nb,
                       'longueurM', t.longueur_m, 'points', t.points
                   ) ORDER BY s.sens, s.nb DESC), '[]'::jsonb)::text
            FROM (
                SELECT p.shape_id, p.sens, count(c.course_id) AS nb
                FROM patron p
                LEFT JOIN course c ON c.patron_id = p.patron_id
                WHERE p.route_id = ?
                GROUP BY p.shape_id, p.sens
            ) s
            JOIN trace t ON t.shape_id = s.shape_id
            """, String.class, routeId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
    }

    @GetMapping("/lignes/{routeId}/arrets")
    public List<ArretDto> arrets(@PathVariable String routeId) {
        verifierLigne(routeId);
        return jdbc.query("""
            SELECT DISTINCT a.stop_id, a.nom, a.latitude, a.longitude, a.accessible_pmr
            FROM patron p
            JOIN patron_arret pa ON pa.patron_id = p.patron_id
            JOIN arret a ON a.stop_id = pa.stop_id
            WHERE p.route_id = ?
            ORDER BY a.nom, a.stop_id
            """, (rs, i) -> new ArretDto(
                rs.getString("stop_id"), rs.getString("nom"),
                rs.getDouble("latitude"), rs.getDouble("longitude"),
                rs.getObject("accessible_pmr", Boolean.class)),
            routeId);
    }

    private void verifierLigne(String routeId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM ligne WHERE route_id = ?",
                Integer.class, routeId);
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ligne inconnue : " + routeId);
        }
    }

    public record SensDto(int sens, String destination) {
    }

    @GetMapping("/lignes/{routeId}/sens")
    public List<SensDto> sens(@PathVariable String routeId) {
        verifierLigne(routeId);
        return jdbc.query("""
            SELECT DISTINCT ON (p.sens) p.sens, p.destination
            FROM patron p
            JOIN course c ON c.patron_id = p.patron_id
            WHERE p.route_id = ? AND p.destination IS NOT NULL
            GROUP BY p.sens, p.destination
            ORDER BY p.sens, count(*) DESC
            """, (rs, i) -> new SensDto(rs.getInt("sens"), rs.getString("destination")), routeId);
    }

    @GetMapping("/lignes/{routeId}/frequences")
    public List<FrequenceDto> frequences(@PathVariable String routeId,
                                         @RequestParam(defaultValue = "semaine") String typeJour) {
        if (!TYPES_JOUR.contains(typeJour)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "typeJour doit valoir semaine, samedi ou dimanche");
        }
        return jdbc.query("""
            SELECT sens, heure, nb_passages
            FROM passages_ligne_heure
            WHERE route_id = ? AND type_jour = ?
            ORDER BY sens, heure
            """, (rs, i) -> new FrequenceDto(
                rs.getInt("sens"), rs.getInt("heure"), rs.getDouble("nb_passages")),
            routeId, typeJour);
    }
}
