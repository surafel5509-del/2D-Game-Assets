import type { Asset, Project } from './types';
import { uid } from './types';

const svg = (w: number, h: number, body: string) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${body}</svg>`;
const uri = (markup: string) => `data:image/svg+xml;charset=utf-8,${encodeURIComponent(markup)}`;
const shade = (hex: string, amt: number) => {
  const n = parseInt(hex.slice(1), 16);
  return '#' + [16, 8, 0].map(shift => Math.min(255, Math.max(0, ((n >> shift) & 255) + amt)).toString(16).padStart(2, '0')).join('');
};
const pack = (id: string, name: string, category: string, markup: string, width: number, height: number, tags: string[], modular?: Asset['modular']): Asset =>
  ({ id: `builtin:${id}`, name, category, kind: 'image', src: uri(markup), width, height, tags,
    license: 'Original procedural artwork • CC0', origin: '2D WORLD Engine', modular });

export interface AssetPart { name: string; x: number; y: number; width: number; height: number; markup: string }
const asMarkup = (p: AssetPart) => `<g transform="translate(${p.x} ${p.y})">${p.markup}</g>`;
const partAsset = (parent: Asset, p: AssetPart): Asset => ({
  id: uid('asset'), name: `${parent.name} / ${p.name}`, category: parent.category, kind: 'image',
  src: uri(svg(p.width, p.height, p.markup)), width: p.width, height: p.height,
  tags: [...parent.tags, 'part'], license: parent.license, origin: parent.origin,
});
export function splitAsset(asset: Asset): { part: Asset; offsetX: number; offsetY: number }[] {
  if (!asset.modular) return [];
  const index = Number(asset.id.split('-').pop()) || 0;
  const parts = asset.modular === 'vehicle' ? vehicleParts(index)
    : asset.modular === 'building' ? buildingParts(index) : characterParts(index, asset.category === 'Enemies');
  return parts.map(p => ({ part: partAsset(asset, p), offsetX: p.x + p.width / 2 - asset.width / 2,
    offsetY: p.y + p.height / 2 - asset.height / 2 }));
}

const vehicleNames = ['City hatchback', 'Sport coupe', 'Police cruiser', 'Yellow taxi', 'Ambulance', 'Fire truck', 'Delivery van', 'School bus', 'Pickup truck', 'Off-road SUV', 'Rally racer', 'Armored rover', 'Farm tractor', 'Cargo truck', 'Electric mini', 'Vintage sedan', 'Street racer'];
const vehicleColors = ['#6fc7e9', '#ec837d', '#a3d47a', '#f4c76e', '#ac9be8', '#f2a969', '#8fd2c1', '#e27ca9', '#b4cbe5'];
function vehicleParts(index: number): AssetPart[] {
  const model = Math.floor(index / 2) % vehicleNames.length;
  const color = vehicleColors[(model + index % 2 * 3) % vehicleColors.length];
  const trim = shade(color, -55);
  const isTall = [4, 5, 6, 7, 9, 11, 12, 13].includes(model);
  const isLong = [5, 7, 13].includes(model);
  const roofY = isTall ? 11 : 19;
  const front = isLong ? 111 : 107;
  const parts: AssetPart[] = [
    { name: 'Rear wheel', x: 20, y: 46, width: 24, height: 24, markup: '<circle cx="12" cy="12" r="11" fill="#19212e" stroke="#0c1320" stroke-width="2"/><circle cx="12" cy="12" r="5.5" fill="#aebdcb" stroke="#748a9c" stroke-width="2"/><circle cx="12" cy="12" r="2" fill="#ecf2e8"/>' },
    { name: 'Front wheel', x: 84, y: 46, width: 24, height: 24, markup: '<circle cx="12" cy="12" r="11" fill="#19212e" stroke="#0c1320" stroke-width="2"/><circle cx="12" cy="12" r="5.5" fill="#aebdcb" stroke="#748a9c" stroke-width="2"/><circle cx="12" cy="12" r="2" fill="#ecf2e8"/>' },
    { name: 'Body', x: 7, y: 28, width: 114, height: 31, markup: `<path d="M7 1h94q8 0 9 8l3 13q1 6-5 6H6q-5 0-5-5V8q0-7 6-7Z" fill="${color}" stroke="${trim}" stroke-width="2"/><path d="M4 22h106v3H4Z" fill="${shade(color, -24)}"/><path d="M45 3v23M73 3v23" stroke="${trim}" opacity=".5" stroke-width="1.4"/>` },
    { name: 'Cabin', x: 26, y: roofY, width: 77, height: 23 + 28 - roofY, markup: `<path d="M${isTall ? 4 : 9} ${isTall ? 1 : 9}h${isLong ? 58 : 48}q7 0 11 7l8 13v8H1v-8Z" fill="${shade(color, 16)}" stroke="${trim}" stroke-width="2"/>` },
    { name: 'Rear window', x: 36, y: roofY + 7, width: 23, height: 17, markup: '<path d="M3 1h18v15H1v-9Z" fill="#8bc5d6" stroke="#456e81" stroke-width="1.5"/><path d="M5 3h8L3 14H2Z" fill="#d2f3ec" opacity=".45"/>' },
    { name: 'Front window', x: 62, y: roofY + 7, width: 26, height: 17, markup: '<path d="M1 1h17l7 14H1Z" fill="#8bc5d6" stroke="#456e81" stroke-width="1.5"/><path d="M6 3h8L3 14H2Z" fill="#d2f3ec" opacity=".5"/>' },
    { name: 'Door', x: 64, y: 36, width: 23, height: 19, markup: `<path d="M1 0v18h20V0" fill="none" stroke="${trim}" opacity=".55"/><path d="M4 5h7" stroke="#fff4d3" stroke-width="2" stroke-linecap="round"/>` },
    { name: 'Headlight', x: front, y: 33, width: 10, height: 9, markup: '<rect x="1" y="1" width="8" height="7" rx="2" fill="#ffe8a0" stroke="#f4b86d"/>' },
    { name: 'Taillight', x: 8, y: 34, width: 7, height: 8, markup: '<rect x="1" y="1" width="6" height="7" rx="2" fill="#f07875"/>' },
    { name: 'Front bumper', x: 109, y: 51, width: 13, height: 6, markup: '<rect width="13" height="6" rx="2" fill="#34495d"/><path d="M2 1h9" stroke="#8297a8"/>' },
  ];
  if ([2, 4, 5].includes(model)) parts.push({ name: model === 2 ? 'Siren' : 'Emergency lights', x: 49, y: roofY - 5, width: 29, height: 8,
    markup: '<rect x="1" y="2" width="27" height="5" rx="2" fill="#27364a"/><rect x="3" y="1" width="10" height="5" rx="2" fill="#5faeee"/><rect x="16" y="1" width="10" height="5" rx="2" fill="#f37878"/>' });
  if ([1, 10, 16].includes(model)) parts.push({ name: 'Spoiler', x: 12, y: 19, width: 20, height: 13,
    markup: `<path d="M3 12V3m14 9V3M0 2h20" stroke="${trim}" stroke-width="3" stroke-linecap="round"/>` });
  if (model === 3) parts.push({ name: 'Taxi sign', x: 53, y: roofY - 6, width: 23, height: 9, markup: '<rect x="1" y="1" width="21" height="8" rx="2" fill="#f8d466" stroke="#735c2e"/><path d="M5 5h13" stroke="#735c2e" stroke-width="2"/>' });
  return parts;
}

const skinColors = ['#f2bb87', '#c98361', '#f3d3aa', '#94644f', '#d9a080', '#ad7959'];
const outfitColors = ['#74c8bd', '#e7a474', '#98a9e9', '#e5c476', '#dc8baf', '#7cb2e3', '#a5d17d', '#bca5df'];
const hairColors = ['#473a43', '#84533d', '#d8a669', '#5d4b74', '#283f55', '#b06450'];
function characterParts(index: number, enemy = false): AssetPart[] {
  const skin = enemy ? ['#99c17b', '#9bb9c9', '#bf9dce', '#e09a83'][index % 4] : skinColors[index % skinColors.length];
  const clothes = enemy ? ['#657b64', '#766a89', '#986c71', '#637b94'][index % 4] : outfitColors[Math.floor(index / 2) % outfitColors.length];
  const hair = enemy ? shade(skin, -48) : hairColors[Math.floor(index / 3) % hairColors.length];
  const hasHat = index % 5 === 1;
  return [
    { name: 'Shadow', x: 11, y: 70, width: 44, height: 8, markup: '<ellipse cx="22" cy="4" rx="21" ry="4" fill="#18272e" opacity=".28"/>' },
    { name: 'Left leg', x: 18, y: 54, width: 14, height: 19, markup: `<path d="M2 0h10l-1 14 2 3H1l2-4Z" fill="${shade(clothes,-35)}" stroke="#344656" stroke-width="1.3"/><path d="M0 17h13" stroke="#233044" stroke-width="3"/>` },
    { name: 'Right leg', x: 34, y: 54, width: 14, height: 19, markup: `<path d="M2 0h10l-1 14 2 3H1l2-4Z" fill="${shade(clothes,-25)}" stroke="#344656" stroke-width="1.3"/><path d="M0 17h13" stroke="#233044" stroke-width="3"/>` },
    { name: 'Left arm', x: 7, y: 38, width: 14, height: 23, markup: `<rect x="3" y="1" width="9" height="17" rx="4" fill="${clothes}" stroke="#405361" stroke-width="1.4"/><circle cx="7" cy="19" r="4" fill="${skin}"/>` },
    { name: 'Right arm', x: 44, y: 38, width: 14, height: 23, markup: `<rect x="2" y="1" width="9" height="17" rx="4" fill="${clothes}" stroke="#405361" stroke-width="1.4"/><circle cx="7" cy="19" r="4" fill="${skin}"/>` },
    { name: 'Body', x: 17, y: 36, width: 31, height: 23, markup: `<path d="M5 1h21l4 21H1Z" fill="${clothes}" stroke="#405361" stroke-width="1.5"/><path d="M5 3h21M9 9h13" stroke="${shade(clothes,35)}" stroke-width="2" opacity=".6"/><path d="M13 2l3 6 3-6" fill="${shade(clothes,-25)}"/>` },
    { name: 'Head', x: 17, y: 14, width: 31, height: 30, markup: `<rect x="2" y="2" width="27" height="26" rx="10" fill="${skin}" stroke="${shade(skin,-35)}" stroke-width="1.5"/><ellipse cx="9" cy="17" rx="2" ry="2.6" fill="#27384c"/><ellipse cx="21" cy="17" rx="2" ry="2.6" fill="#27384c"/><path d="M13 23q2 2 5 0" fill="none" stroke="${shade(skin,-65)}" stroke-width="1.5" stroke-linecap="round"/>` },
    { name: hasHat ? 'Hat' : 'Hair', x: 16, y: 8, width: 33, height: 18, markup: hasHat
      ? `<path d="M5 6q11-9 23 0v8H5Z" fill="${hair}" stroke="#304353" stroke-width="2"/><path d="M1 14h31" stroke="${shade(hair,-25)}" stroke-width="4" stroke-linecap="round"/>`
      : `<path d="M2 17Q1 2 13 2h9q11 1 10 15l-5-6-4-5-4 4-9-2-3 8Z" fill="${hair}" stroke="${shade(hair,-25)}" stroke-width="1.5"/>` },
  ];
}

function buildingParts(index: number): AssetPart[] {
  const palette = ['#cf9f78', '#a2bac7', '#e0c08c', '#b4b5a1', '#d49a91', '#98b1a5'];
  const wall = palette[index % palette.length];
  const roof = ['#8e6264', '#547181', '#ba7d62', '#646b7c'][Math.floor(index / 2) % 4];
  return [
    { name: 'Walls', x: 12, y: 33, width: 72, height: 64, markup: `<rect x="1" y="1" width="70" height="62" rx="3" fill="${wall}" stroke="${shade(wall,-35)}" stroke-width="2"/><path d="M2 18h68M2 42h68" stroke="${shade(wall,-15)}" opacity=".45"/>` },
    { name: 'Roof', x: 6, y: 9, width: 84, height: 31, markup: `<path d="M2 27 40 2q2-2 4 0l38 25-3 3H5Z" fill="${roof}" stroke="${shade(roof,-35)}" stroke-width="2"/><path d="M10 25 41 5l31 20" fill="none" stroke="${shade(roof,25)}" stroke-width="2" opacity=".7"/>` },
    { name: 'Left window', x: 20, y: 49, width: 19, height: 22, markup: '<rect x="1" y="1" width="17" height="20" rx="2" fill="#729db0" stroke="#415b6c" stroke-width="3"/><path d="M9 1v20M1 11h17" stroke="#d8e7d9" stroke-width="2"/>' },
    { name: 'Right window', x: 58, y: 49, width: 19, height: 22, markup: '<rect x="1" y="1" width="17" height="20" rx="2" fill="#729db0" stroke="#415b6c" stroke-width="3"/><path d="M9 1v20M1 11h17" stroke="#d8e7d9" stroke-width="2"/>' },
    { name: 'Door', x: 40, y: 66, width: 19, height: 31, markup: '<path d="M1 30V5Q1 1 5 1h9q4 0 4 4v25Z" fill="#71564c" stroke="#463e41" stroke-width="2"/><circle cx="14" cy="17" r="1.5" fill="#e8c878"/>' },
  ];
}

function animalMarkup(index: number): string {
  const colors = ['#d8a877', '#a2b6c7', '#dfa69d', '#b6ad86', '#d1c4a0', '#85a99b', '#c7a5c0', '#a6b88f'];
  const c = colors[index % colors.length]; const dark = shade(c, -48);
  const type = index % 8;
  const ears = type === 3 ? `<path d="M19 28V5q0-3 5-2l5 22M37 26l3-23q3-3 5 2l3 24" fill="${c}" stroke="${dark}" stroke-width="2"/>`
    : type === 2 ? `<path d="M13 28 14 9l14 12M38 20 50 9l1 20" fill="${c}" stroke="${dark}" stroke-width="2"/>`
    : `<path d="M17 27 13 14q0-4 5-4l9 12M38 22l9-12q5-1 5 4l-4 13" fill="${c}" stroke="${dark}" stroke-width="2"/>`;
  const body = type === 4 ? `<ellipse cx="32" cy="47" rx="22" ry="16" fill="${dark}"/><ellipse cx="32" cy="43" rx="17" ry="14" fill="${c}"/><path d="M19 43q13-14 27 0" fill="none" stroke="${dark}" stroke-width="2"/>`
    : type === 7 ? `<path d="M7 47q23-28 47 0Q30 66 7 47Zm0 0-6-10v20Z" fill="${c}" stroke="${dark}" stroke-width="2"/>`
    : `<ellipse cx="32" cy="49" rx="20" ry="13" fill="${c}" stroke="${dark}" stroke-width="2"/>`;
  return svg(64, 72, `<ellipse cx="32" cy="66" rx="20" ry="4" fill="#203246" opacity=".18"/>${body}${ears}<circle cx="32" cy="32" r="17" fill="${c}" stroke="${dark}" stroke-width="2"/><ellipse cx="25" cy="32" rx="2.5" ry="3" fill="#253747"/><ellipse cx="39" cy="32" rx="2.5" ry="3" fill="#253747"/><path d="m29 41 3 2 3-2" fill="none" stroke="${dark}" stroke-width="2" stroke-linecap="round"/><circle cx="17" cy="61" r="4" fill="${dark}"/><circle cx="47" cy="61" r="4" fill="${dark}"/>`);
}
function natureMarkup(index: number): string {
  const type = index % 7;
  const greens = ['#6aa982', '#7dbb8c', '#9dbb72', '#64a89c', '#b5bc83', '#8faa69'];
  const c = greens[Math.floor(index / 7) % greens.length];
  const dark = shade(c, -40);
  if (type < 3) return svg(80, 90, `<ellipse cx="40" cy="84" rx="26" ry="5" fill="#223445" opacity=".2"/><path d="M33 49h14l3 34H30Z" fill="#8c6957" stroke="#5c5147" stroke-width="2"/><path d="M37 78V31" stroke="#ad8465" stroke-width="2"/><circle cx="25" cy="48" r="18" fill="${dark}"/><circle cx="54" cy="47" r="19" fill="${dark}"/><circle cx="40" cy="28" r="24" fill="${c}" stroke="${dark}" stroke-width="2"/><circle cx="29" cy="25" r="6" fill="${shade(c,18)}" opacity=".6"/><circle cx="50" cy="37" r="5" fill="${shade(c,18)}" opacity=".6"/>`);
  if (type === 3) return svg(80, 90, `<ellipse cx="40" cy="82" rx="30" ry="6" fill="#263a47" opacity=".18"/><ellipse cx="40" cy="62" rx="31" ry="19" fill="${dark}"/><circle cx="25" cy="58" r="14" fill="${c}"/><circle cx="54" cy="57" r="16" fill="${c}"/><circle cx="40" cy="48" r="18" fill="${shade(c,12)}"/>`);
  if (type === 4) return svg(80, 90, '<ellipse cx="40" cy="83" rx="29" ry="5" fill="#263a47" opacity=".17"/><path d="M12 75 24 47l28-17 15 22 3 23Z" fill="#929ba0" stroke="#65717a" stroke-width="2"/><path d="m24 47 16 8 12-25M40 55l-8 20" fill="none" stroke="#c2c7bd" stroke-width="2"/>');
  if (type === 5) return svg(80, 90, `<path d="M40 80V38" stroke="#5b8b68" stroke-width="4"/><path d="M40 69 24 58m16-5 14-12" stroke="#6a9d6b" stroke-width="2"/><circle cx="40" cy="29" r="12" fill="#f4c780"/><circle cx="40" cy="13" r="9" fill="${c}"/><circle cx="55" cy="22" r="9" fill="${c}"/><circle cx="54" cy="39" r="9" fill="${c}"/><circle cx="26" cy="39" r="9" fill="${c}"/><circle cx="25" cy="22" r="9" fill="${c}"/><circle cx="40" cy="29" r="8" fill="#f4c780"/>`);
  return svg(80, 90, `<path d="M12 77q14-33 27-6 15-32 29 5" fill="${c}" stroke="${dark}" stroke-width="2"/><path d="M15 76 9 54m21 21-3-36m23 35 3-44m13 45 5-29" fill="none" stroke="${dark}" stroke-width="3" stroke-linecap="round"/>`);
}
const propNames = ['Wooden crate', 'Iron barrel', 'Treasure chest', 'Stone pillar', 'Street lamp', 'Wooden sign', 'Health potion', 'Mana potion', 'Gold coin', 'Blue gem', 'Magic scroll', 'Wooden shield', 'Steel sword', 'Campfire', 'Computer terminal', 'Traffic cone', 'Road barrier', 'Lantern', 'Bookshelf', 'Market stall', 'Wooden bench', 'Potted plant', 'Supply box', 'Crystal cluster', 'Dungeon torch', 'Metal pipe', 'Flag', 'Iron gate', 'Gear', 'Key'];
function propMarkup(index: number): string {
  const n = index % 30, v = Math.floor(index / 30);
  const c = ['#e3b978', '#9bc6bf', '#bca5dd'][v % 3];
  const shadow = '<ellipse cx="32" cy="58" rx="23" ry="4" fill="#1b2836" opacity=".18"/>';
  const shapes = [
    `<rect x="12" y="15" width="40" height="40" rx="3" fill="#ac794f" stroke="#664e43" stroke-width="3"/><path d="M14 17 50 53M50 17 14 53M12 25h40M12 46h40" stroke="#d5a46b" stroke-width="3"/>`,
    '<ellipse cx="32" cy="16" rx="17" ry="6" fill="#788b97"/><path d="M15 16v35q0 8 17 8t17-8V16" fill="#7b939e" stroke="#435663" stroke-width="3"/><path d="M16 27h32M16 45h32" stroke="#b5c7c4" stroke-width="3"/>',
    `<rect x="9" y="25" width="46" height="31" rx="3" fill="#855d4c" stroke="#443d42" stroke-width="3"/><path d="M9 26V17q23-17 46 0v9Z" fill="${c}" stroke="#51484c" stroke-width="3"/><rect x="28" y="25" width="8" height="17" rx="2" fill="#f4cf77"/>`,
    '<path d="M15 55h34l-3-6V18H18v31Z" fill="#9da8a6" stroke="#697b80" stroke-width="3"/><path d="M12 18h40v-6H12ZM11 55h42v5H11Z" fill="#c3ccbf" stroke="#697b80" stroke-width="2"/>',
    '<path d="M28 59V22h8v37" fill="#68798a"/><path d="M20 20q0-15 12-15t12 15Z" fill="#eecb84" stroke="#636e79" stroke-width="3"/><ellipse cx="32" cy="19" rx="12" ry="4" fill="#ffeaad"/>',
    `<path d="M30 57V9" stroke="#795844" stroke-width="5"/><path d="M9 14h42v24H9Z" fill="${c}" stroke="#75604b" stroke-width="3"/><path d="m20 27 19-7-3 7 3 7Z" fill="#fff5d0" opacity=".6"/>`,
    '<path d="M24 10h16v8l-3 4v5l9 22q4 9-6 9H24q-10 0-6-9l9-22v-5l-3-4Z" fill="#eab5b3" stroke="#627c8d" stroke-width="3"/><path d="M23 41h18l4 11q1 4-5 4H24q-6 0-5-4Z" fill="#ef696e"/>',
    '<path d="M24 10h16v8l-3 4v5l9 22q4 9-6 9H24q-10 0-6-9l9-22v-5l-3-4Z" fill="#b4d9e8" stroke="#627c8d" stroke-width="3"/><path d="M23 41h18l4 11q1 4-5 4H24q-6 0-5-4Z" fill="#719ddf"/>',
    '<circle cx="32" cy="32" r="24" fill="#d8984d" stroke="#8c6040" stroke-width="3"/><circle cx="32" cy="32" r="18" fill="#f5ca70" stroke="#fff0a6" stroke-width="2"/><path d="M35 19 23 35h10l-5 10 13-16H30Z" fill="#fff4bb"/>',
    '<path d="m32 4 22 24-22 32L10 28Z" fill="#82d6ec" stroke="#3c829d" stroke-width="3"/><path d="M10 28h44M32 4v56" stroke="#d7fcfc" stroke-width="2" opacity=".8"/>',
    '<path d="M18 8h32v39H18Z" fill="#e5d4a4" stroke="#8b7656" stroke-width="3"/><circle cx="18" cy="11" r="5" fill="#9c7458"/><circle cx="18" cy="46" r="5" fill="#9c7458"/><path d="M27 17h17M27 26h17M27 35h11" stroke="#a78e6a" stroke-width="2"/>',
    '<path d="m32 4 20 10-3 29-17 16-17-16-3-29Z" fill="#9bb3bd" stroke="#425767" stroke-width="3"/><path d="M32 10v43M18 20h28" stroke="#e5d8aa" stroke-width="3"/>',
    '<path d="m33 4 7 6-5 25 4 13-7 13-7-13 4-13-5-25Z" fill="#c8d3d3" stroke="#667e87" stroke-width="3"/><path d="M18 38h29" stroke="#79694f" stroke-width="5"/><path d="M29 47h8" stroke="#906648" stroke-width="4"/>',
    '<path d="M16 50q8-9 13-2-9-20-2-28 8 8 8 21 2-20 12-24 5 20-8 31 9-8 12 3Z" fill="#f5a45f" stroke="#dc6453" stroke-width="3"/><path d="M16 56h35" stroke="#5a4b45" stroke-width="4"/>',
    '<rect x="8" y="8" width="48" height="42" rx="4" fill="#415c71" stroke="#293d50" stroke-width="3"/><rect x="13" y="13" width="38" height="27" fill="#89c9c6"/><path d="M25 56h14m-7-6v6" stroke="#809eab" stroke-width="4"/>',
    '<path d="m31 7 20 49H13Z" fill="#ef945f" stroke="#995a44" stroke-width="3"/><path d="M24 30h16M19 44h26" stroke="#fff0cf" stroke-width="5"/>',
    '<path d="M7 25h50v25H7Z" fill="#d4a977" stroke="#63584d" stroke-width="3"/><path d="M11 31h42M15 44h34" stroke="#fff5d9" stroke-width="4"/><path d="M14 50v8m36-8v8" stroke="#576878" stroke-width="3"/>',
    '<path d="M30 55V28h5v27" stroke="#657783" stroke-width="4"/><path d="M20 28q0-18 12-18t12 18Z" fill="#f6d58f" stroke="#727986" stroke-width="3"/><path d="M25 31h14" stroke="#e9b86e" stroke-width="3"/>',
    '<rect x="12" y="10" width="40" height="48" rx="2" fill="#916c58" stroke="#594e4c" stroke-width="3"/><path d="M14 26h36M14 41h36" stroke="#d3b17c" stroke-width="3"/><path d="M18 13v11m8-11v11m8-11v11m8-11v11" stroke="#8bbdc4" stroke-width="5"/>',
    `<path d="M7 23h50l-5 10H12Z" fill="${c}" stroke="#6b5852" stroke-width="3"/><path d="M13 33v24m38-24v24" stroke="#7e5b4c" stroke-width="4"/><path d="M16 42h33" stroke="#b9956c" stroke-width="3"/>`,
    '<path d="M9 34h46v7H9Zm6 8v14m34-14v14" fill="#b48761" stroke="#66564e" stroke-width="4"/><path d="M14 31h36" stroke="#e0bb83" stroke-width="3"/>',
    `<path d="M26 58h14l4-17H22Z" fill="#b28670" stroke="#806259" stroke-width="2"/><path d="M32 42V14m0 20-14-10m14 5 14-11" stroke="#608c6d" stroke-width="3"/><circle cx="25" cy="20" r="13" fill="${c}"/><circle cx="43" cy="17" r="11" fill="#81b89b"/>`,
    '<rect x="11" y="16" width="42" height="40" rx="3" fill="#91a5a7" stroke="#465f6b" stroke-width="3"/><path d="M11 30h42M23 16v40M40 16v40" stroke="#d0d8c4" stroke-width="3"/>',
    '<path d="m32 5 11 21 13 6-18 7-6 20-10-20-15-8 17-6Z" fill="#9bb6eb" stroke="#6a73a8" stroke-width="3"/><path d="m32 5 6 34-6 20-8-34Z" fill="#d9d5f6"/>',
    '<path d="M28 56V32" stroke="#5b5350" stroke-width="4"/><path d="M17 32q-3-22 15-26 17 4 15 26Z" fill="#f3b763" stroke="#e57857" stroke-width="3"/><path d="M19 34h26" stroke="#866150" stroke-width="4"/>',
    '<path d="M8 32h48v10H8Z" fill="#8196a2" stroke="#4e6571" stroke-width="3"/><circle cx="14" cy="37" r="3" fill="#bed3ce"/><circle cx="50" cy="37" r="3" fill="#bed3ce"/>',
    `<path d="M17 8v52" stroke="#6a6559" stroke-width="4"/><path d="M21 11h36L45 25l12 14H21Z" fill="${c}" stroke="#6e5965" stroke-width="2"/>`,
    '<path d="M12 57V18h40v39" fill="none" stroke="#7c8792" stroke-width="6"/><path d="M18 23v28m8-28v28m8-28v28m8-28v28" stroke="#7c8792" stroke-width="3"/>',
    '<circle cx="32" cy="32" r="21" fill="#83959b" stroke="#566973" stroke-width="3"/><circle cx="32" cy="32" r="11" fill="#344b5b" stroke="#bac9c8" stroke-width="2"/><path d="M32 4v9m0 38v9M4 32h9m38 0h9" stroke="#83959b" stroke-width="7"/>',
    '<path d="m26 36 10-10 10 10-7 7-10-10-13 13q-5 5-1 8 4 4 8 0Z" fill="#e2c36c" stroke="#8b7955" stroke-width="3"/><circle cx="44" cy="18" r="10" fill="none" stroke="#e2c36c" stroke-width="5"/>',
  ];
  return svg(64, 64, shadow + shapes[n]);
}
const materials: [name: string, base: string, accent: string, style: number][] = [
  ['Meadow grass','#79a862','#b3cf80',0], ['Forest grass','#4c8567','#8db07b',0], ['Dry grass','#baaf70','#e0cf90',0],
  ['Rich soil','#775b4c','#a37c5b',1], ['Desert sand','#d8bc87','#f0d5a7',1], ['Red clay','#ac7861','#d9a17c',1],
  ['Granite','#879399','#b2bdb9',2], ['Limestone','#b6b5a4','#d4d2b9',2], ['Snow','#d9e7e7','#ffffff',1],
  ['Ice','#a6d6df','#d4f3ee',3], ['Ocean water','#3289ad','#8ad0d4',3], ['River water','#4aa5af','#a2d9cc',3],
  ['Oak wood','#9a7458','#c59e72',4], ['Pine wood','#987c61','#d6b792',4], ['Dark metal','#53646d','#96aab0',5],
  ['Steel plate','#899ba4','#ccd5d0',5], ['Concrete','#9d9f9a','#c1c7bd',2], ['Red brick','#ab7160','#d19a7e',6],
  ['Asphalt','#4d565b','#788188',2], ['Roof tiles','#9b6561','#c68a78',6], ['Glass','#7cbdc8','#c4eaeb',3],
  ['Fabric','#ae8eac','#dbc1d4',7], ['Mud','#786951','#a48e6b',1], ['Lava','#993f38','#f18c48',3],
];
function textureMarkup(index: number): string {
  const material = materials[Math.floor(index / 5) % materials.length];
  const variant = index % 5;
  const [, base, accent, style] = material;
  let pieces = `<rect width="64" height="64" fill="${base}"/>`;
  const rnd = (i: number) => { const x = Math.sin(i * 127.1 + index * 53.7) * 43758.5453; return x - Math.floor(x); };
  if (style === 4) for (let i = 0; i < 9; i++) pieces += `<path d="M0 ${i * 8 + variant}q18 ${rnd(i) * 4 - 2} 34 0t30 0" stroke="${accent}" stroke-width="${i % 3 === 0 ? 2 : 1}" opacity=".65" fill="none"/>`;
  else if (style === 6) for (let y = 0; y < 4; y++) for (let x = 0; x < 3; x++) pieces += `<rect x="${x * 23 - (y % 2) * 11 - 2}" y="${y * 17 - 2}" width="21" height="15" rx="1" fill="${shade(base, Math.floor(rnd(x + y * 3) * 24 - 9))}" stroke="${accent}" stroke-opacity=".55" stroke-width="1"/>`;
  else if (style === 3) for (let i = 0; i < 7; i++) pieces += `<path d="M${-12 + i * 13} ${i * 11 % 65}q8 -5 18 0t22 0" fill="none" stroke="${accent}" stroke-opacity=".65" stroke-width="${variant % 3 + 1.5}"/>`;
  else if (style === 5) { for (let i = 0; i < 5; i++) pieces += `<path d="M${i * 16 + variant} 0v64M0 ${i * 16 + variant}h64" stroke="${accent}" stroke-opacity=".35" stroke-width="1"/>`; pieces += '<circle cx="9" cy="9" r="2" fill="#e5e3cf" opacity=".5"/><circle cx="57" cy="56" r="2" fill="#e5e3cf" opacity=".5"/>'; }
  else for (let i = 0; i < 34; i++) { const x = Math.floor(rnd(i * 3 + 5) * 64), y = Math.floor(rnd(i * 3 + 6) * 64);
    pieces += style === 0 ? `<path d="M${x} ${y + 3}l${variant % 2 ? 3 : -2}-5m2 5 3-4" fill="none" stroke="${accent}" stroke-opacity=".7" stroke-width="1.5"/>`
      : style === 7 ? `<path d="M${x} ${y}h4" stroke="${accent}" stroke-opacity=".6" stroke-width="1"/>`
      : `<ellipse cx="${x}" cy="${y}" rx="${2 + rnd(i) * 3}" ry="${1 + rnd(i + 1) * 2}" fill="${i % 3 ? accent : shade(base,-20)}" opacity=".45"/>`; }
  return svg(64, 64, pieces);
}

const sfxNames = ['UI click', 'UI hover', 'Jump', 'Footstep', 'Hit', 'Explosion', 'Laser shot', 'Engine start', 'Engine idle', 'Brake', 'Door open', 'Coin', 'Power up', 'Damage', 'Game over', 'Menu open', 'Notification', 'Success', 'Failure', 'Wind ambience', 'Water splash', 'Magic cast', 'Sparkle', 'Enemy spawn', 'Shield', 'Teleport', 'Level complete', 'Countdown', 'Fire crackle', 'Heartbeat'];
const sfxIds = ['click','hover','jump','step','hit','explosion','laser','engine-start','engine','brake','door','coin','powerup','damage','gameover','menu','notification','success','failure','wind','splash','magic','sparkle','spawn','shield','teleport','level','countdown','fire','heartbeat'];
function makeLibrary(): Asset[] {
  const result: Asset[] = [];
  for (let i = 0; i < 36; i++) {
    const parts = characterParts(i);
    result.push(pack(`player-${i}`, `${['Scout','Explorer','Knight','Builder','Pilot','Mage','Ranger','Guardian','Traveler'][Math.floor(i / 4)]} ${['Amber','Cobalt','Jade','Rose'][i % 4]}`, 'Characters', svg(64, 80, parts.map(asMarkup).join('')), 64, 80, ['character','player','modular'], 'character'));
  }
  for (let i = 0; i < 36; i++) {
    const parts = characterParts(i, true);
    result.push(pack(`enemy-${i}`, `${['Slime guard','Goblin','Automaton','Wraith','Bandit','Golem','Specter','Marauder','Sentinel'][Math.floor(i / 4)]} ${['I','II','III','IV'][i % 4]}`, 'Enemies', svg(64, 80, parts.map(asMarkup).join('')), 64, 80, ['enemy','character','modular'], 'character'));
  }
  for (let i = 0; i < 16; i++) {
    const parts = characterParts(i + 16);
    result.push(pack(`npc-${i}`, `${['Villager','Merchant','Healer','Archivist'][Math.floor(i / 4)]} ${['A','B','C','D'][i % 4]}`, 'NPCs', svg(64, 80, parts.map(asMarkup).join('')), 64, 80, ['npc','character','modular'], 'character'));
  }
  for (let i = 0; i < 24; i++) result.push(pack(`animal-${i}`, `${['Cat','Dog','Fox','Rabbit','Turtle','Bear','Owl','Fish'][i % 8]} ${['Woodland','Coastal','Highland'][Math.floor(i / 8)]}`, 'Animals', animalMarkup(i), 64, 72, ['animal','creature']));
  for (let i = 0; i < 34; i++) result.push(pack(`vehicle-${i}`, `${vehicleNames[Math.floor(i / 2)]} ${i % 2 ? '— alternate' : ''}`.trim(), 'Vehicles', svg(128, 72, vehicleParts(i).map(asMarkup).join('')), 128, 72, ['vehicle','car','modular'], 'vehicle'));
  for (let i = 0; i < 24; i++) result.push(pack(`building-${i}`, `${['Cottage','Workshop','Townhouse','Market','Inn','Watchtower'][Math.floor(i / 4)]} ${['01','02','03','04'][i % 4]}`, 'Buildings', svg(96, 100, buildingParts(i).map(asMarkup).join('')), 96, 100, ['building','modular'], 'building'));
  for (let i = 0; i < 42; i++) result.push(pack(`nature-${i}`, `${['Oak tree','Pine tree','Orchard tree','Hedge','Boulder','Wildflower','Tall grass'][i % 7]} ${Math.floor(i / 7) + 1}`, 'Nature', natureMarkup(i), 80, 90, ['environment','nature']));
  for (let i = 0; i < 30; i++) result.push(pack(`prop-${i}`, propNames[i], 'Props', propMarkup(i), 64, 64, ['prop','object']));
  for (let i = 0; i < 120; i++) {
    const name = materials[Math.floor(i / 5)][0];
    result.push(pack(`texture-${i}`, `${name} · ${['fine','coarse','light','worn','detail'][i % 5]}`, 'Textures', textureMarkup(i), 64, 64, ['texture','tileable',name.toLowerCase()]));
  }
  for (let i = 0; i < 180; i++) result.push({ id: `builtin:tile-${i}`, name: `Platformer terrain · ${String(i + 1).padStart(3,'0')}`, category: 'Tiles', kind: 'image',
    src: `./assets/kenney/tiles/tile_${String(i).padStart(4,'0')}.png`, width: 18, height: 18, tags: ['kenney','pixel','terrain'], license: 'Kenney Pixel Platformer • CC0', origin: 'Kenney (kenney.nl)' });
  for (let i = 0; i < 27; i++) result.push({ id: `builtin:kenney-character-${i}`, name: `Platformer character frame · ${String(i + 1).padStart(2,'0')}`, category: 'Characters', kind: 'image',
    src: `./assets/kenney/characters/tile_${String(i).padStart(4,'0')}.png`, width: 24, height: 24, tags: ['kenney','pixel','character'], license: 'Kenney Pixel Platformer • CC0', origin: 'Kenney (kenney.nl)' });
  for (let i = 0; i < 24; i++) result.push({ id: `builtin:background-${i}`, name: `Platformer backdrop · ${String(i + 1).padStart(2,'0')}`, category: 'Terrain', kind: 'image',
    src: `./assets/kenney/backgrounds/tile_${String(i).padStart(4,'0')}.png`, width: 18, height: 18, tags: ['kenney','pixel','background'], license: 'Kenney Pixel Platformer • CC0', origin: 'Kenney (kenney.nl)' });
  for (let i = 0; i < sfxNames.length; i++) result.push({ id: `builtin:sfx-${sfxIds[i]}`, name: sfxNames[i], category: 'Audio', kind: 'audio',
    src: `./assets/sfx/${sfxIds[i]}.wav`, width: 0, height: 0, tags: ['sound','sfx','generated'],
    license: 'Original synthesized audio • CC0', origin: '2D WORLD Engine' });
  return result;
}
export const BUILTIN_ASSETS = makeLibrary();
export const BUILTIN_MAP = new Map(BUILTIN_ASSETS.map(a => [a.id, a]));
export const ASSET_CATEGORIES = ['All', 'Characters', 'Enemies', 'NPCs', 'Animals', 'Vehicles', 'Buildings', 'Nature', 'Props', 'Textures', 'Tiles', 'Terrain', 'Audio', 'Imported'];
export function getAsset(project: Project, id: string): Asset | undefined {
  return BUILTIN_MAP.get(id) ?? project.assets.find(asset => asset.id === id);
}
export function allAssets(project: Project): Asset[] { return [...project.assets, ...BUILTIN_ASSETS]; }
export function assetUseCount(project: Project, id: string): number {
  return project.scenes.reduce((total, scene) => total + scene.nodes.reduce((count, node) => count +
    node.components.filter(c => (c.type === 'sprite' || c.type === 'audio') && c.assetId === id).length +
    (node.components.find(c => c.type === 'tilemap' && Object.values(c.cells).includes(id)) ? 1 : 0), 0), 0);
}
