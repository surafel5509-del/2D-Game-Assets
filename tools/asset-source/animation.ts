import type { Animation, Property, Track } from './types';
import { uid } from './types';

export function animationTime(animation: Animation, seconds: number): number {
  if (animation.duration <= 0) return 0;
  if (animation.loop === 'once') return Math.min(seconds, animation.duration);
  if (animation.loop === 'pingpong') {
    const phase = seconds % (animation.duration * 2);
    return phase > animation.duration ? animation.duration * 2 - phase : phase;
  }
  return seconds % animation.duration;
}
export function sampleTrack(track: Track, time: number): number {
  const keys = [...track.keys].sort((a, b) => a.time - b.time);
  if (!keys.length) return 0;
  if (time <= keys[0].time) return keys[0].value;
  for (let i = 1; i < keys.length; i++) {
    if (time <= keys[i].time) {
      const t = (time - keys[i - 1].time) / Math.max(0.0001, keys[i].time - keys[i - 1].time);
      return keys[i - 1].value + (keys[i].value - keys[i - 1].value) * t;
    }
  }
  return keys[keys.length - 1].value;
}
export function sampleAnimation(animation: Animation, seconds: number): Partial<Record<Property, number>> {
  const time = animationTime(animation, seconds);
  return Object.fromEntries(animation.tracks.map(track => [track.property, sampleTrack(track, time)]));
}
export function frameAt(animation: Animation, seconds: number): string | undefined {
  const time = animationTime(animation, seconds);
  return [...(animation.frames ?? [])].sort((a, b) => a.time - b.time).filter(frame => frame.time <= time).at(-1)?.assetId ?? animation.frames?.[0]?.assetId;
}
const motionNames = [
  'Character · Idle', 'Character · Walk', 'Character · Run', 'Character · Jump', 'Character · Fall',
  'Character · Attack', 'Character · Hit', 'Character · Hurt', 'Character · Death', 'Character · Roll',
  'Character · Dash', 'Character · Shoot', 'Character · Reload', 'Character · Climb', 'Character · Swim',
  'Enemy · Idle', 'Enemy · Patrol', 'Enemy · Attack', 'Enemy · Chase', 'Enemy · Hurt', 'Enemy · Death', 'Enemy · Special attack',
  'Animal · Idle', 'Animal · Walk', 'Animal · Run', 'Animal · Jump', 'Animal · Attack', 'Animal · Fly', 'Animal · Swim',
  'Vehicle · Idle', 'Vehicle · Drive', 'Vehicle · Brake', 'Vehicle · Turn', 'Vehicle · Crash', 'Vehicle · Bounce',
  'Environment · Water', 'Environment · Fire', 'Environment · Smoke', 'Environment · Grass sway',
  'Environment · Tree sway', 'Environment · Wind', 'Environment · Electricity', 'Environment · Machine',
  'UI · Pulse', 'UI · Slide in', 'UI · Pop', 'UI · Float', 'UI · Shake', 'UI · Fade in', 'UI · Fade out', 'UI · Bounce',
  'Effects · Sparkle', 'Effects · Flash', 'Effects · Spin', 'Effects · Wobble', 'Effects · Grow', 'Effects · Shrink',
  'Effects · Hover', 'Effects · Recoil', 'Effects · Teleport',
];
const curve = (property: Property, values: number[], duration: number): Track => ({
  property, keys: values.map((value, i) => ({ time: (i / (values.length - 1)) * duration, value })),
});
export function motionPreset(name: string): Animation {
  const action = name.split(' · ')[1] ?? 'Idle';
  const fast = /Run|Dash|Chase|Attack|Shoot|Sparkle|Electricity|Shake/.test(action);
  const duration = fast ? 0.45 : /Death|Fade|Slide|Teleport/.test(action) ? 0.9 : 0.8;
  let tracks: Track[];
  if (/Walk|Run|Patrol|Chase|Drive|Swim|Climb/.test(action)) tracks = [
    curve('y', [0, fast ? -8 : -4, 0, fast ? -8 : -4, 0], duration),
    curve('rotation', [0, fast ? 8 : 4, 0, fast ? -8 : -4, 0], duration),
  ];
  else if (/Jump|Bounce|Pop/.test(action)) tracks = [curve('y', [0, -26, -36, -18, 0], duration), curve('scaleY', [0.8, 1.12, 1, 0.95, 0.8], duration)];
  else if (/Fall|Death|Crash/.test(action)) tracks = [curve('y', [0, 8, 32], duration), curve('rotation', [0, 20, 78], duration), curve('opacity', [1, 1, 0.2], duration)];
  else if (/Hit|Hurt|Shake|Recoil/.test(action)) tracks = [curve('x', [0, -11, 9, -6, 3, 0], duration), curve('rotation', [0, -9, 8, 0], duration)];
  else if (/Spin|Roll/.test(action)) tracks = [curve('rotation', [0, 120, 240, 360], duration)];
  else if (/Fade in/.test(action)) tracks = [curve('opacity', [0, 0.5, 1], duration)];
  else if (/Fade out|Smoke/.test(action)) tracks = [curve('opacity', [1, 0.6, 0], duration), curve('y', [0, -15, -40], duration)];
  else if (/Grow|Pulse|Fire|Special attack|Sparkle/.test(action)) tracks = [curve('scaleX', [1, 1.18, 0.88, 1], duration), curve('scaleY', [1, 1.18, 0.88, 1], duration)];
  else if (/Shrink/.test(action)) tracks = [curve('scaleX', [1, 0.7, 0.2], duration), curve('scaleY', [1, 0.7, 0.2], duration)];
  else if (/Slide in|Dash/.test(action)) tracks = [curve('x', [-48, -12, 0], duration), curve('opacity', [0, 0.8, 1], duration)];
  else if (/Attack|Shoot|Reload|Brake|Turn|Wobble|Machine|Electricity/.test(action)) tracks = [curve('rotation', [0, -14, 10, -4, 0], duration), curve('x', [0, 5, -3, 0], duration)];
  else if (/Teleport|Flash/.test(action)) tracks = [curve('opacity', [1, 0, 1, 0, 1], duration), curve('scaleX', [1, 0.6, 1.3, 1], duration)];
  else tracks = [curve('y', [0, -5, 0], duration), curve('scaleY', [1, 1.04, 1], duration)];
  return { id: uid('animation'), name, duration, fps: 12, loop: /Death|Crash|Fall|Fade|Slide|Teleport/.test(action) ? 'once' : 'loop', tracks, events: [] };
}
export const MOTION_PRESETS = motionNames;

export interface ParticlePreset { name: string; rate: number; speed: number; lifetime: number; spread: number; gravity: number; size: number; color: string }
const particleFamilies: ParticlePreset[] = [
  { name: 'Fire', rate: 34, speed: 65, lifetime: 0.9, spread: 45, gravity: -90, size: 8, color: '#ff9d63' },
  { name: 'Smoke', rate: 18, speed: 25, lifetime: 2, spread: 55, gravity: -32, size: 14, color: '#a0aab7' },
  { name: 'Dust', rate: 28, speed: 38, lifetime: 0.7, spread: 180, gravity: 20, size: 4, color: '#ccb890' },
  { name: 'Rain', rate: 45, speed: 130, lifetime: 1.2, spread: 12, gravity: 250, size: 2, color: '#82cbe6' },
  { name: 'Snow', rate: 20, speed: 15, lifetime: 3, spread: 200, gravity: 20, size: 4, color: '#f2f7ff' },
  { name: 'Sparks', rate: 36, speed: 125, lifetime: 0.5, spread: 360, gravity: 130, size: 3, color: '#ffdc75' },
  { name: 'Explosion', rate: 90, speed: 200, lifetime: 0.4, spread: 360, gravity: 45, size: 7, color: '#ff896a' },
  { name: 'Magic', rate: 24, speed: 55, lifetime: 1, spread: 360, gravity: -15, size: 5, color: '#a596ed' },
  { name: 'Energy', rate: 38, speed: 90, lifetime: 0.65, spread: 360, gravity: 0, size: 4, color: '#6fdfda' },
  { name: 'Lightning', rate: 50, speed: 170, lifetime: 0.3, spread: 60, gravity: 0, size: 3, color: '#c2d8ff' },
  { name: 'Water splash', rate: 36, speed: 100, lifetime: 0.75, spread: 160, gravity: 160, size: 5, color: '#7ed1e8' },
  { name: 'Sand burst', rate: 28, speed: 85, lifetime: 0.9, spread: 230, gravity: 110, size: 3, color: '#dfc487' },
  { name: 'Leaves', rate: 12, speed: 35, lifetime: 2.5, spread: 180, gravity: 25, size: 7, color: '#9cc980' },
  { name: 'Confetti', rate: 42, speed: 95, lifetime: 1.6, spread: 260, gravity: 80, size: 4, color: '#eb8cba' },
  { name: 'Fog', rate: 10, speed: 12, lifetime: 3.8, spread: 180, gravity: -4, size: 24, color: '#a8b9c4' },
  { name: 'Steam', rate: 18, speed: 42, lifetime: 1.4, spread: 40, gravity: -70, size: 8, color: '#dae3e5' },
  { name: 'Rocket exhaust', rate: 52, speed: 155, lifetime: 0.55, spread: 35, gravity: 20, size: 6, color: '#ffc675' },
  { name: 'Impact', rate: 54, speed: 130, lifetime: 0.35, spread: 360, gravity: 55, size: 4, color: '#f58b83' },
  { name: 'Healing', rate: 20, speed: 40, lifetime: 1.2, spread: 170, gravity: -55, size: 5, color: '#9be9ae' },
  { name: 'Teleport', rate: 38, speed: 115, lifetime: 0.85, spread: 360, gravity: -20, size: 4, color: '#a9a6f7' },
];
export const PARTICLE_PRESETS: ParticlePreset[] = particleFamilies.flatMap((p, i) =>
  ['Standard','Soft','Burst','Fine','Wide'].map((variant, j) => ({ ...p,
    name: `${p.name} · ${variant}`, rate: Math.round(p.rate * [1, 0.6, 2, 0.8, 1.1][j]),
    speed: Math.round(p.speed * [1, 0.7, 1.45, 0.9, 1][j]),
    size: Math.max(1, Math.round(p.size * [1, 1.4, 1.1, 0.5, 1][j])),
    spread: Math.min(360, p.spread * [1, 1, 1.3, 1, 1.8][j]),
    color: j === 3 ? ['#f3d9a7','#b8d6ef','#d3c4ef','#abe7d1'][i % 4] : p.color,
  })));
