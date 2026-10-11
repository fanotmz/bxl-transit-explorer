import { Component, DestroyRef, ElementRef, afterNextRender, computed, inject, signal, viewChild } from '@angular/core';
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

interface Sens {
  sens: number;
  destination: string;
}

interface PositionVehicule {
  courseId: string;
  sens: number;
  destination: string | null;
  latitude: number;
  longitude: number;
}

type Mode = 'tous' | 'metro' | 'tram' | 'bus';
type TypeJour = 'semaine' | 'samedi' | 'dimanche';

function minutesMaintenant(): number {
  const d = new Date();
  return d.getHours() * 60 + d.getMinutes();
}

function dateLocale(): string {
  const d = new Date();
  const mois = String(d.getMonth() + 1).padStart(2, '0');
  const jour = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${mois}-${jour}`;
}

@Component({
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly carteEl = viewChild.required<ElementRef<HTMLDivElement>>('carte');
  private carte?: L.Map;
  private readonly couche = L.layerGroup();
  private readonly vehiculesLayer = L.layerGroup();
  private readonly vehicules = new Map<string, L.CircleMarker>();
  private requete = 0;
  private requetePositions = 0;
  private minuteur?: ReturnType<typeof setTimeout>;

  readonly modes: Mode[] = ['tous', 'metro', 'tram', 'bus'];
  readonly typesJour: TypeJour[] = ['semaine', 'samedi', 'dimanche'];

  readonly lignes = signal<Ligne[]>([]);
  readonly recherche = signal('');
  readonly mode = signal<Mode>('tous');
  readonly choisie = signal<Ligne | null>(null);
  readonly typeJour = signal<TypeJour>('semaine');
  readonly frequences = signal<Frequence[]>([]);
  readonly sens = signal<Sens[]>([]);
  readonly nbArrets = signal(0);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly enDirect = signal(true);
  readonly minutes = signal(minutesMaintenant());
  readonly nbVehicules = signal(0);
  readonly positionsOk = signal(true);

  readonly heureTexte = computed(() => {
    const m = this.minutes();
    return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
  });

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
        info: `${heure % 24} h - ${this.nomSens(0)} : ${format(p0)} - ${this.nomSens(1)} : ${format(p1)}`,
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
      this.vehiculesLayer.addTo(this.carte);
    });

    this.http.get<Ligne[]>('/api/lignes').subscribe({
      next: (l) => this.lignes.set(l),
      error: () => this.erreur.set('Impossible de charger les lignes. Le backend tourne-t-il ?'),
    });

    const horloge = setInterval(() => {
      if (this.enDirect()) {
        this.minutes.set(minutesMaintenant());
        this.chargerPositions();
      }
    }, 15000);
    this.destroyRef.onDestroy(() => clearInterval(horloge));
  }

  couleur(l: Ligne): string {
    return '#' + (l.couleur ?? '666666');
  }

  couleurTexte(l: Ligne): string {
    return '#' + (l.couleurTexte ?? 'FFFFFF');
  }

  nomSens(numero: number): string {
    const s = this.sens().find((x) => x.sens === numero);
    return s ? 'vers ' + s.destination : 'sens ' + numero;
  }

  selectionner(ligne: Ligne): void {
    this.choisie.set(ligne);
    this.erreur.set(null);
    this.chargement.set(true);
    this.vehiculesLayer.clearLayers();
    this.vehicules.clear();
    this.nbVehicules.set(0);
    this.sens.set([]);
    const id = ++this.requete;
    const cle = encodeURIComponent(ligne.id);
    forkJoin({
      traces: this.http.get<Trace[]>(`/api/lignes/${cle}/traces`),
      arrets: this.http.get<Arret[]>(`/api/lignes/${cle}/arrets`),
      sens: this.http.get<Sens[]>(`/api/lignes/${cle}/sens`),
      frequences: this.http.get<Frequence[]>(`/api/lignes/${cle}/frequences`, {
        params: { typeJour: this.typeJour() },
      }),
    }).subscribe({
      next: ({ traces, arrets, sens, frequences }) => {
        if (id !== this.requete) {
          return;
        }
        this.sens.set(sens);
        this.frequences.set(frequences);
        this.nbArrets.set(arrets.length);
        this.dessiner(ligne, traces, arrets);
        this.chargement.set(false);
        this.chargerPositions();
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

  basculerDirect(): void {
    const actif = !this.enDirect();
    this.enDirect.set(actif);
    if (actif) {
      this.minutes.set(minutesMaintenant());
      this.chargerPositions();
    }
  }

  changerMinutes(valeur: number): void {
    this.enDirect.set(false);
    this.minutes.set(valeur);
    clearTimeout(this.minuteur);
    this.minuteur = setTimeout(() => this.chargerPositions(), 150);
  }

  private chargerPositions(): void {
    const ligne = this.choisie();
    if (!ligne) {
      return;
    }
    const params: Record<string, string> = {};
    if (!this.enDirect()) {
      params['date'] = dateLocale();
      params['heure'] = this.heureTexte();
    }
    const id = ++this.requetePositions;
    this.http
      .get<PositionVehicule[]>(`/api/lignes/${encodeURIComponent(ligne.id)}/positions`, { params })
      .subscribe({
        next: (positions) => {
          if (id === this.requetePositions) {
            this.positionsOk.set(true);
            this.nbVehicules.set(positions.length);
            this.afficherPositions(positions);
          }
        },
        error: () => {
          if (id === this.requetePositions) {
            this.positionsOk.set(false);
          }
        },
      });
  }

  private afficherPositions(positions: PositionVehicule[]): void {
    const vus = new Set<string>();
    for (const p of positions) {
      vus.add(p.courseId);
      const cible = L.latLng(p.latitude, p.longitude);
      const existant = this.vehicules.get(p.courseId);
      if (existant) {
        if (this.enDirect()) {
          this.animer(existant, cible);
        } else {
          existant.setLatLng(cible);
        }
      } else {
        const marqueur = L.circleMarker(cible, {
          radius: 7,
          color: '#ffffff',
          weight: 2,
          fillColor: p.sens === 0 ? '#2f6fed' : '#f08a24',
          fillOpacity: 1,
        });
        marqueur.bindTooltip(this.texte('Vers ' + (p.destination ?? 'destination inconnue')));
        marqueur.addTo(this.vehiculesLayer);
        this.vehicules.set(p.courseId, marqueur);
      }
    }
    for (const [id, marqueur] of this.vehicules) {
      if (!vus.has(id)) {
        marqueur.remove();
        this.vehicules.delete(id);
      }
    }
  }

  private animer(marqueur: L.CircleMarker, cible: L.LatLng): void {
    const depart = marqueur.getLatLng();
    const debut = performance.now();
    const duree = 900;
    const pas = (maintenant: number) => {
      const f = Math.min(1, (maintenant - debut) / duree);
      marqueur.setLatLng(
        L.latLng(depart.lat + (cible.lat - depart.lat) * f, depart.lng + (cible.lng - depart.lng) * f),
      );
      if (f < 1) {
        requestAnimationFrame(pas);
      }
    };
    requestAnimationFrame(pas);
  }

  private texte(contenu: string): HTMLElement {
    const element = document.createElement('span');
    element.textContent = contenu;
    return element;
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
      const pmr =
        a.accessiblePmr === true ? ' (accessible PMR)' : a.accessiblePmr === false ? ' (non accessible PMR)' : '';
      L.circleMarker([a.latitude, a.longitude], {
        radius: 4,
        color: '#222',
        weight: 1,
        fillColor: '#fff',
        fillOpacity: 1,
      })
        .bindTooltip(this.texte(a.nom + pmr))
        .addTo(this.couche);
    }

    if (bounds.isValid()) {
      this.carte.fitBounds(bounds, { padding: [24, 24] });
    }
  }
}
