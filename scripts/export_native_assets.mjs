/** Convert the original CC0 vector library into raster art bundled inside the Android app. */
import { build } from 'esbuild';
import { Resvg } from '@resvg/resvg-js';
import fs from 'node:fs';
import path from 'node:path';
import JSZip from 'jszip';
import { pathToFileURL } from 'node:url';
const root = 'app/src/main/assets/library';
const kenney = await JSZip.loadAsync(fs.readFileSync('kenney_pixel-platformer.zip'));
const outfile = '/tmp/world2d-assets-export.mjs';
await build({ entryPoints: ['tools/asset-source/assets.ts'], bundle: true, platform: 'node', format: 'esm', outfile });
const { BUILTIN_ASSETS, splitAsset } = await import(pathToFileURL(outfile).href + '?t=' + Date.now());
const imageDir = path.join(root, 'images');
const partDir = path.join(root, 'parts');
const audioDir = path.join(root, 'audio');
for (const dir of [imageDir, partDir, audioDir]) fs.mkdirSync(dir, { recursive: true });
const records = [];
for (const asset of BUILTIN_ASSETS) {
  const file = asset.id.replace(/[^a-z0-9-]/gi, '_');
  const record = { id: asset.id, name: asset.name, category: asset.category, kind: asset.kind,
    width: asset.width, height: asset.height, tags: asset.tags, license: asset.license, origin: asset.origin,
    modular: asset.modular ?? null, parts: [] };
  if (asset.src.startsWith('data:image/svg+xml')) {
    const xml = decodeURIComponent(asset.src.split(',').slice(1).join(','));
    const buffer = new Resvg(xml, { fitTo: { mode: 'zoom', value: 2 } }).render().asPng();
    fs.writeFileSync(path.join(imageDir, file + '.png'), buffer);
    record.path = `library/images/${file}.png`;
  } else {
    const origin = asset.src.replace(/^\.\//,'');
    const extension = path.extname(origin);
    const destDir = asset.kind === 'audio' ? audioDir : imageDir;
    if (asset.kind === 'audio') fs.copyFileSync(path.join('tools/generated-sfx', path.basename(origin)), path.join(destDir, file + extension));
    else {
      const entry = origin.replace('assets/kenney/tiles/', 'Tiles/').replace('assets/kenney/characters/', 'Tiles/Characters/').replace('assets/kenney/backgrounds/', 'Tiles/Backgrounds/');
      fs.writeFileSync(path.join(destDir, file + extension), await kenney.file(entry).async('nodebuffer'));
    }
    record.path = `library/${asset.kind === 'audio' ? 'audio' : 'images'}/${file}${extension}`;
  }
  if (asset.modular) {
    const parts = splitAsset(asset);
    const dest = path.join(partDir, file);
    fs.mkdirSync(dest, { recursive: true });
    for (let i = 0; i < parts.length; i++) {
      const item = parts[i];
      const xml = decodeURIComponent(item.part.src.split(',').slice(1).join(','));
      const buffer = new Resvg(xml, { fitTo: { mode: 'zoom', value: 2 } }).render().asPng();
      fs.writeFileSync(path.join(dest, `${i}.png`), buffer);
      record.parts.push({ name: item.part.name.split(' / ').at(-1), path: `library/parts/${file}/${i}.png`,
        width: item.part.width, height: item.part.height, x: item.offsetX, y: item.offsetY });
    }
  }
  records.push(record);
}
fs.writeFileSync(path.join(root, 'catalog.json'), JSON.stringify(records));
fs.writeFileSync(path.join(root, 'LICENSES.txt'), 'Kenney Pixel Platformer by Kenney (kenney.nl): Creative Commons Zero (CC0).\n2D WORLD Engine procedural vector art and synthesized sound effects: original work, CC0.\nImported user assets retain their own licenses.\n');
console.log(`Exported ${records.length} resources including ${records.reduce((n,r)=>n+r.parts.length,0)} independent modular components.`);

await build({ entryPoints: ['tools/asset-source/animation.ts'], bundle: true, platform: 'node', format: 'esm', outfile: '/tmp/world2d-presets-export.mjs' });
const { MOTION_PRESETS, motionPreset, PARTICLE_PRESETS } = await import(pathToFileURL('/tmp/world2d-presets-export.mjs').href + '?t=' + Date.now());
fs.writeFileSync(path.join(root, 'motion-presets.json'), JSON.stringify(MOTION_PRESETS.map(name => motionPreset(name))));
fs.writeFileSync(path.join(root, 'particle-presets.json'), JSON.stringify(PARTICLE_PRESETS));
