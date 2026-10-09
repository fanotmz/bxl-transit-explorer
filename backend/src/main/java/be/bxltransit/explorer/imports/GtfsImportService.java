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
import java.util.List;
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
