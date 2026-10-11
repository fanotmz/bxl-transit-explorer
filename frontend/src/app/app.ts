import { Component, ElementRef, afterNextRender, computed, inject, signal, viewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { forkJoin } from 'rxjs';
import * as L from 'leaflet';

interface Ligne {
  id: string;
  numero: string;
  nom: string | null;
  mode: 'tram' | 'metro' | 'bus';
  couleur: string | null;
  couleurTexte: string | null;
}

interface Trace {
  shapeId: string;
  sens: number;
  courses: number;
  longueurM: number;
  points: [number, number, number][];
}

interface Arret {
  id: string;
  nom: string;
  latitude: number;
  longitude: number;
  accessiblePmr: boolean | null;
}

interface Frequence {
  sens: number;
  heure: number;
  passages: number;
}

type Mode = 'tous' | 'metro' | 'tram' | 'bus';
type TypeJour = 'semaine' | 'samedi' | 'dimanche';

@Component({
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly http = inject(HttpClient);
  private readonly carteEl = viewChild.required<ElementRef<HTMLDivElement>>('carte');
  private carte?: L.Map;
  private readonly couche = L.layerGroup();
  private requete = 0;

  readonly modes: Mode[] = ['tous', 'metro', 'tram', 'bus'];
  readonly typesJour: TypeJour[] = ['semaine', 'samedi', 'dimanche'];

  readonly lignes = signal<Ligne[]>([]);
  readonly recherche = signal('');
  readonly mode = signal<Mode>('tous');
  readonly choisie = signal<Ligne | null>(null);
  readonly typeJour = signal<TypeJour>('semaine');
  readonly frequences = signal<Frequence[]>([]);
  readonly nbArrets = signal(0);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);

  readonly lignesFiltrees = computed(() => {
    const texte = this.recherche().trim().toLowerCase();
    const mode = this.mode();
    return this.lignes().filter(
      (l) =>
        (mode === 'tous' || l.mode === mode) &&
        (texte === '' ||
          l.numero.toLowerCase().includes(texte) ||
          (l.nom ?? '').toLowerCase().includes(texte)),
    );
  });

  readonly barres = computed(() => {
    const f = this.frequences();
    if (f.length === 0) {
      return [];
    }
    const heures = f.map((x) => x.heure);
    const debut = Math.min(...heures);
    const fin = Math.max(...heures);
    const max = Math.max(...f.map((x) => x.passages));
    const valeur = (sens: number, heure: number) =>
      f.find((x) => x.sens === sens && x.heure === heure)?.passages ?? 0;
    const format = (n: number) => n.toLocaleString('fr-BE', { maximumFractionDigits: 1 });
    return Array.from({ length: fin - debut + 1 }, (_, i) => {
      const heure = debut + i;
      const p0 = valeur(0, heure);
      const p1 = valeur(1, heure);
      return {
        heure,
        h0: (p0 / max) * 100,
        h1: (p1 / max) * 100,
        info: `${heure % 24} h - sens 0 : ${format(p0)} - sens 1 : ${format(p1)}`,
      };
    });
  });

  constructor() {
    afterNextRender(() => {
      this.carte = L.map(this.carteEl().nativeElement).setView([50.8503, 4.3517], 12);
      L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; contributeurs OpenStreetMap',
      }).addTo(this.carte);
      this.couche.addTo(this.carte);
    });

    this.http.get<Ligne[]>('/api/lignes').subscribe({
      next: (l) => this.lignes.set(l),
      error: () => this.erreur.set('Impossible de charger les lignes. Le backend tourne-t-il ?'),
    });
  }

  couleur(l: Ligne): string {
    return '#' + (l.couleur ?? '666666');
  }

  couleurTexte(l: Ligne): string {
    return '#' + (l.couleurTexte ?? 'FFFFFF');
  }

  selectionner(ligne: Ligne): void {
    this.choisie.set(ligne);
    this.erreur.set(null);
    this.chargement.set(true);
    const id = ++this.requete;
    const cle = encodeURIComponent(ligne.id);
    forkJoin({
      traces: this.http.get<Trace[]>(`/api/lignes/${cle}/traces`),
      arrets: this.http.get<Arret[]>(`/api/lignes/${cle}/arrets`),
      frequences: this.http.get<Frequence[]>(`/api/lignes/${cle}/frequences`, {
        params: { typeJour: this.typeJour() },
      }),
    }).subscribe({
      next: ({ traces, arrets, frequences }) => {
        if (id !== this.requete) {
          return;
        }
        this.frequences.set(frequences);
        this.nbArrets.set(arrets.length);
        this.dessiner(ligne, traces, arrets);
        this.chargement.set(false);
      },
      error: () => {
        if (id === this.requete) {
          this.erreur.set('Impossible de charger cette ligne.');
          this.chargement.set(false);
        }
      },
    });
  }

  changerTypeJour(type: TypeJour): void {
    this.typeJour.set(type);
    const ligne = this.choisie();
    if (!ligne) {
      return;
    }
    this.http
      .get<Frequence[]>(`/api/lignes/${encodeURIComponent(ligne.id)}/frequences`, {
        params: { typeJour: type },
      })
      .subscribe((f) => this.frequences.set(f));
  }

  private dessiner(ligne: Ligne, traces: Trace[], arrets: Arret[]): void {
    if (!this.carte) {
      return;
    }
    this.couche.clearLayers();
    const couleur = this.couleur(ligne);
    const dejaVus = new Set<number>();
    const bounds = L.latLngBounds([]);

    for (const t of traces) {
      const pts = t.points.map((p) => [p[0], p[1]] as L.LatLngTuple);
      const principal = !dejaVus.has(t.sens);
      dejaVus.add(t.sens);
      L.polyline(pts, {
        color: couleur,
        weight: principal ? 5 : 2,
        opacity: principal ? 0.9 : 0.3,
      }).addTo(this.couche);
      if (principal) {
        bounds.extend(pts);
      }
    }

    for (const a of arrets) {
      const info = document.createElement('span');
      const pmr =
        a.accessiblePmr === true ? ' (accessible PMR)' : a.accessiblePmr === false ? ' (non accessible PMR)' : '';
      info.textContent = a.nom + pmr;
      L.circleMarker([a.latitude, a.longitude], {
        radius: 4,
        color: '#222',
        weight: 1,
        fillColor: '#fff',
        fillOpacity: 1,
      })
        .bindTooltip(info)
        .addTo(this.couche);
    }

    if (bounds.isValid()) {
      this.carte.fitBounds(bounds, { padding: [24, 24] });
    }
  }
}
