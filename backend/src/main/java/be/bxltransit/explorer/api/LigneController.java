package be.bxltransit.explorer.api;

import java.util.List;

import org.springframework.http.HttpStatus;
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
