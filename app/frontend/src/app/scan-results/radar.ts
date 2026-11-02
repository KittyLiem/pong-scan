import { DomainScore } from '../core/model/results';
import { formatScore } from '../shared/labels';

export interface RadarAxis {
  label: string;
  score: number;
  x2: number;
  y2: number;
  pointX: number;
  pointY: number;
  labelX: number;
  labelY: number;
  anchor: 'start' | 'middle' | 'end';
}

export interface RadarGeometry {
  size: number;
  /** Breed genoeg voor de geschatte breedte van alle labels, zodat lange domeinnamen niet worden afgekapt. */
  viewBox: string;
  rings: string[];
  axes: RadarAxis[];
  polygon: string;
}

const MAX_SCORE = 5;
const FONT_SIZE = 12;
/** Ruwe gemiddelde tekenbreedte; liever iets te ruim dan afgekapt. */
const CHAR_WIDTH = FONT_SIZE * 0.6;

/** Tekst van een aslabel, zoals getoond in de template. */
export function radarLabelText(axis: Pick<RadarAxis, 'label' | 'score'>): string {
  return `${axis.label} (${formatScore(axis.score)})`;
}

/** Berekent een radarvisualisatie (inline SVG) voor domeinscores op een schaal van 0–5. */
export function radarGeometry(scores: DomainScore[], size = 420, padding = 70, minSidePadding = 20): RadarGeometry {
  const center = size / 2;
  const radius = center - padding;
  const count = scores.length;
  const angle = (index: number) => -Math.PI / 2 + (2 * Math.PI * index) / Math.max(count, 1);
  const at = (index: number, value: number) => {
    const r = (radius * Math.max(0, Math.min(MAX_SCORE, value))) / MAX_SCORE;
    return { x: center + r * Math.cos(angle(index)), y: center + r * Math.sin(angle(index)) };
  };
  const round = (value: number) => Math.round(value * 10) / 10;

  const rings = [1, 2, 3, 4, 5].map(level =>
    Array.from({ length: Math.max(count, 3) }, (_, index) => {
      const ringAngle = -Math.PI / 2 + (2 * Math.PI * index) / Math.max(count, 3);
      const r = (radius * level) / MAX_SCORE;
      return `${round(center + r * Math.cos(ringAngle))},${round(center + r * Math.sin(ringAngle))}`;
    }).join(' '),
  );

  const axes = scores.map((score, index) => {
    const end = at(index, MAX_SCORE);
    const point = at(index, score.score);
    const cos = Math.cos(angle(index));
    const labelRadius = radius + 16;
    return {
      label: score.domainName,
      score: score.score,
      x2: round(end.x),
      y2: round(end.y),
      pointX: round(point.x),
      pointY: round(point.y),
      labelX: round(center + labelRadius * cos),
      labelY: round(center + labelRadius * Math.sin(angle(index)) + 4),
      anchor: Math.abs(cos) < 0.2 ? ('middle' as const) : cos > 0 ? ('start' as const) : ('end' as const),
    };
  });

  const extents = axes.map(axis => {
    const width = radarLabelText(axis).length * CHAR_WIDTH;
    const left = axis.anchor === 'start' ? axis.labelX : axis.anchor === 'end' ? axis.labelX - width : axis.labelX - width / 2;
    return { left, right: left + width };
  });
  const minX = Math.min(-minSidePadding, ...extents.map(extent => extent.left - minSidePadding));
  const maxX = Math.max(size + minSidePadding, ...extents.map(extent => extent.right + minSidePadding));

  return { size, viewBox: `${round(minX)} 0 ${round(maxX - minX)} ${size}`, rings, axes, polygon: axes.map(axis => `${axis.pointX},${axis.pointY}`).join(' ') };
}
