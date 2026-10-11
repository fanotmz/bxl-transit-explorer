package be.bxltransit.explorer.imports;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class GtfsImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GtfsImportRunner.class);

    private final GtfsImportService service;
    private final boolean actif;
    private final Path dossier;

    public GtfsImportRunner(GtfsImportService service,
                            @Value("${bxl.import.enabled:false}") boolean actif,
                            @Value("${bxl.import.gtfs-dir:../data/gtfs}") String dossier) {
        this.service = service;
        this.actif = actif;
        this.dossier = Path.of(dossier);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!actif) {
            return;
        }
        int lignes = service.importLignes(dossier);
        int arrets = service.importArrets(dossier);
        int jours = service.importServiceJours(dossier);
        int traces = service.importTraces(dossier);
        int[] horaires = service.importHoraires(dossier);
        log.info("Import GTFS termine : {} lignes, {} arrets, {} jours de service, {} traces", lignes, arrets, jours, traces);
        log.info("Horaires : {} patrons, {} arrets de patron, {} courses", horaires[0], horaires[1], horaires[2]);
        int agregats = service.calculerAgregats();
        log.info("Agregats : {} lignes de passages par ligne et par heure", agregats);
        double[] dist = service.calculerDistancesArrets();
        log.info("Distances : {} arrets de patron, ecart moyen au trace {} m, maximum {} m, {} a plus de 50 m", (int) dist[0], Math.round(dist[1] * 10) / 10.0, Math.round(dist[2]), (int) dist[3]);
    }
}
