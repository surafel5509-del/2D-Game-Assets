export type BodyMode = 'static' | 'dynamic' | 'character';
export type Genre = 'empty' | 'platformer' | 'rpg' | 'shooter' | 'racing' | 'adventure' | 'puzzle' | 'arcade';
export type Property = 'x' | 'y' | 'rotation' | 'scaleX' | 'scaleY' | 'opacity';

export interface Transform {
  x: number; y: number; width: number; height: number;
  rotation: number; scaleX: number; scaleY: number; opacity: number;
}
export type Component =
  | { type: 'sprite'; assetId: string; tint: string }
  | { type: 'body'; mode: BodyMode; gravityScale: number; mass: number; friction: number; bounce: number; vx: number; vy: number }
  | { type: 'collider'; shape: 'box' | 'circle'; width: number; height: number; radius: number; sensor: boolean; offsetX: number; offsetY: number }
  | { type: 'controller'; style: 'platformer' | 'topdown' | 'car'; speed: number; jump: number }
  | { type: 'camera'; zoom: number; smoothing: number; follow: boolean }
  | { type: 'particles'; preset: string; rate: number; speed: number; lifetime: number; spread: number; gravity: number; size: number; color: string; emitting: boolean }
  | { type: 'audio'; assetId: string; volume: number; loop: boolean; autoplay: boolean }
  | { type: 'script'; scriptId: string }
  | { type: 'animation'; animationId: string; autoplay: boolean; speed: number }
  | { type: 'tilemap'; tileSize: number; columns: number; rows: number; cells: Record<string, string>; collision: boolean }
  | { type: 'label'; text: string; fontSize: number; color: string; align: 'left' | 'center' | 'right' }
  | { type: 'ai'; behavior: 'patrol' | 'chase' | 'wander' | 'follow'; speed: number; range: number }
  | { type: 'light'; radius: number; color: string; intensity: number }
  | { type: 'timer'; interval: number; repeat: boolean; enabled: boolean };

export type ComponentType = Component['type'];
export interface Node {
  id: string; name: string; parentId: string | null; transform: Transform;
  components: Component[]; visible: boolean; locked: boolean; layer: number;
  tags: string[]; prefabId?: string; metadata?: Record<string, string>;
}
export interface Scene {
  id: string; name: string; width: number; height: number; background: string;
  nodes: Node[];
}
export interface Asset {
  id: string; name: string; category: string; kind: 'image' | 'audio';
  src: string; width: number; height: number; tags: string[];
  license: string; origin: string; modular?: 'vehicle' | 'character' | 'building';
  favorite?: boolean;
}
export interface Keyframe { time: number; value: number }
export interface Track { property: Property; keys: Keyframe[] }
export interface Animation {
  id: string; name: string; duration: number; fps: number; loop: 'loop' | 'once' | 'pingpong';
  tracks: Track[]; events: { time: number; action: string }[];
  frames?: { time: number; assetId: string }[];
}
export interface Script { id: string; name: string; source: string }
export interface Prefab { id: string; name: string; nodes: Node[] }
export interface Project {
  format: 1; id: string; name: string; packageId: string; version: string;
  genre: Genre; orientation: 'landscape' | 'portrait' | 'auto';
  width: number; height: number; gravity: number; fps: number;
  scenes: Scene[]; startSceneId: string; assets: Asset[];
  scripts: Script[]; animations: Animation[]; prefabs: Prefab[];
  favorites: string[]; input: Record<string, string[]>;
  createdAt: number; updatedAt: number;
}
export interface LogEntry { id: string; level: 'info' | 'warn' | 'error'; message: string; time: number }
export interface RuntimeStats { fps: number; frameMs: number; objects: number; particles: number; collisions: number; drawCalls: number }

export function uid(prefix = 'id'): string {
  return `${prefix}_${globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2)}`;
}
export function component<T extends ComponentType>(node: Node, type: T): Extract<Component, { type: T }> | undefined {
  return node.components.find((item): item is Extract<Component, { type: T }> => item.type === type);
}
export const defaultTransform = (x = 0, y = 0, width = 48, height = 48): Transform =>
  ({ x, y, width, height, rotation: 0, scaleX: 1, scaleY: 1, opacity: 1 });
export const defaultInput: Record<string, string[]> = {
  left: ['ArrowLeft', 'KeyA'], right: ['ArrowRight', 'KeyD'],
  up: ['ArrowUp', 'KeyW'], down: ['ArrowDown', 'KeyS'],
  jump: ['Space'], fire: ['KeyJ', 'Enter'], interact: ['KeyE'], pause: ['Escape'],
};
export function makeNode(name: string, x: number, y: number, width = 48, height = 48, components: Component[] = []): Node {
  return { id: uid('node'), name, parentId: null, transform: defaultTransform(x, y, width, height),
    components, visible: true, locked: false, layer: 0, tags: [] };
}
export function makeScene(name: string, width = 960, height = 540, background = '#192839'): Scene {
  return { id: uid('scene'), name, width, height, background, nodes: [] };
}
export function makeProject(name: string, genre: Genre = 'empty', width = 960, height = 540): Project {
  const now = Date.now();
  const scene = makeScene('Main Scene', width, height);
  const slug = name.toLowerCase().replace(/[^a-z0-9]+/g, '').slice(0, 24) || 'mygame';
  return { format: 1, id: uid('project'), name, packageId: `com.world2d.${slug}`, version: '1.0.0',
    genre, orientation: 'landscape', width, height, gravity: 800, fps: 60,
    scenes: [scene], startSceneId: scene.id, assets: [], scripts: [], animations: [], prefabs: [],
    favorites: [], input: structuredClone(defaultInput), createdAt: now, updatedAt: now };
}
export function defaultComponent(type: ComponentType): Component {
  switch (type) {
    case 'sprite': return { type, assetId: 'builtin:player-0', tint: '#ffffff' };
    case 'body': return { type, mode: 'dynamic', gravityScale: 1, mass: 1, friction: 0.2, bounce: 0, vx: 0, vy: 0 };
    case 'collider': return { type, shape: 'box', width: 40, height: 40, radius: 20, sensor: false, offsetX: 0, offsetY: 0 };
    case 'controller': return { type, style: 'platformer', speed: 220, jump: 390 };
    case 'camera': return { type, zoom: 1, smoothing: 0.1, follow: true };
    case 'particles': return { type, preset: 'Sparkles', rate: 12, speed: 65, lifetime: 0.8, spread: 360, gravity: -20, size: 5, color: '#a9eb78', emitting: true };
    case 'audio': return { type, assetId: 'builtin:sfx-coin', volume: 0.8, loop: false, autoplay: false };
    case 'script': return { type, scriptId: '' };
    case 'animation': return { type, animationId: '', autoplay: true, speed: 1 };
    case 'tilemap': return { type, tileSize: 48, columns: 20, rows: 12, cells: {}, collision: false };
    case 'label': return { type, text: 'New label', fontSize: 24, color: '#ffffff', align: 'center' };
    case 'ai': return { type, behavior: 'patrol', speed: 60, range: 120 };
    case 'light': return { type, radius: 160, color: '#ffe7ac', intensity: 0.65 };
    case 'timer': return { type, interval: 2, repeat: true, enabled: true };
  }
}
