"""実列を撮影と同じ視点で透視投影する。材質・霧・流体透過は再現しない。"""
import argparse
import json
import math
from pathlib import Path
import numpy as np
from PIL import Image
from map_image import read_samples


def trace(samples, job, camera, fov, width, height, pixel_step=1, pixels=None):
    """1B列を透視投影し、RGBと命中面を返す。

    cameraのYは足元、yaw/pitchと補正後の垂直FOVは度数。幅・高さは画素数。
    samplesはground/top/flagsを持ち、jobのboundsとstep=1に一致する列配列。
    命中面の末尾5値はX/Y/Z・軸(0=X,1=Y,2=Z)・法線の符号。空は全て−1。
    pixelsを指定する場合は照会する画素のX/Y対で、戻り値もその順の1次元列。
    """
    if job['step'] != 1:
        raise ValueError('透視図は1B標本を指定してください')
    yaw, pitch = map(math.radians, [camera['yaw'], camera['pitch']])
    forward = np.array([-math.sin(yaw)*math.cos(pitch), -math.sin(pitch), math.cos(yaw)*math.cos(pitch)])
    right = np.array([-math.cos(yaw), 0, -math.sin(yaw)])
    up = np.cross(right, forward)
    eye = np.array([camera['x'], camera['y']+1.62, camera['z']])
    if pixels is None:
        yy, xx = np.mgrid[0:height:pixel_step, 0:width:pixel_step]
    else:
        xx, yy = np.asarray(pixels).T
    focal = height/(2*math.tan(math.radians(fov)/2))
    direction = forward + ((xx.ravel()+pixel_step*.5-width/2)/focal)[:, None]*right
    direction -= ((yy.ravel()+pixel_step*.5-height/2)/focal)[:, None]*up
    sign = np.sign(direction).astype(int)
    delta = np.divide(1, np.abs(direction), out=np.full_like(direction, np.inf), where=direction != 0)
    point = np.tile(np.floor(eye).astype(int), (len(direction), 1))
    travel = np.divide(np.where(sign > 0, point+1-eye, eye-point), np.abs(direction),
                       out=np.full_like(direction, np.inf), where=direction != 0)
    normal = np.ones(len(direction), np.int8)
    result = np.full((len(direction), 5), -1, np.int32)
    color = np.full((len(direction), 3), [163, 184, 212], np.uint8)
    top = np.maximum(samples['ground'], samples['top']).astype(int)
    wet = (samples['flags'] & 3) > 0
    x0, z0 = job['bounds'][:2]
    lo = np.array([x0, -128, z0])
    hi = np.array([x0+top.shape[1]-1, 575, z0+top.shape[0]-1])
    active = np.arange(len(direction))
    for _ in range(top.shape[0]+top.shape[1]+704):
        if not len(active):
            break
        points = point[active]
        inside = ((points >= lo) & (points <= hi)).all(axis=1)
        active = active[inside]
        points = point[active]
        solid = points[:, 1] <= top[points[:, 2]-z0, points[:, 0]-x0]
        hit = active[solid]
        pos = point[hit]
        result[hit, :3] = pos
        result[hit, 3] = normal[hit]
        result[hit, 4] = -sign[hit, normal[hit]]
        shade = np.where(normal[hit] == 1, 170, np.where(normal[hit] == 0, 90, 115))
        color[hit] = shade[:, None]
        water = wet[pos[:, 2]-z0, pos[:, 0]-x0]
        color[hit[water]] = [48, 133, 177]
        active = active[~solid]
        axes = travel[active].argmin(axis=1)
        normal[active] = axes
        point[active, axes] += sign[active, axes]
        travel[active, axes] += delta[active, axes]
    return color.reshape(yy.shape+(3,)), result.reshape(yy.shape+(5,))





def rectangular_hollows(samples):
    """水列を除き、同高の両岸に挟まれた幅1～2Bの直線的なくぼみの最大長を返す。"""
    h = samples['ground'].astype(int)
    dry = (samples['flags'] & 3) == 0
    longest = 0
    for axis in [0, 1]:
        heights, land = (h, dry) if axis==0 else (h.T, dry.T)
        for width in [1, 2]:
            for x in range(1, heights.shape[1]-width):
                run, previous = 0, None
                for z in range(heights.shape[0]):
                    floor = int(heights[z, x])
                    bank = int(heights[z, x-1])
                    valid = (land[z, x-1:x+width+1].all() and bank==heights[z, x+width]
                             and bank>floor and np.all(heights[z, x:x+width]==floor))
                    pair = (floor, bank)
                    run = (run+1 if pair==previous else 1) if valid else 0
                    longest = max(longest, run)
                    previous = pair
    return longest


def _triangle_fraction(yy, xx):
    points = sorted(set((int(xx[yy==row].min()), int(row)) for row in np.unique(yy)) |
                    set((int(xx[yy==row].max()), int(row)) for row in np.unique(yy)))
    def cross(a, b, c):
        return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
    hull = []
    for order in [points, points[::-1]]:
        half = []
        for p in order:
            while len(half)>=2 and cross(half[-2],half[-1],p)<=0:
                half.pop()
            half.append(p)
        hull.extend(half[:-1])
    area = abs(sum(a[0]*b[1]-a[1]*b[0] for a,b in zip(hull,hull[1:]+hull[:1])))
    triangle = max((abs(cross(a,b,c)) for i,a in enumerate(hull) for j,b in enumerate(hull[i+1:],i+1)
                    for c in hull[j+1:]), default=0)
    return triangle/area if area else 0

def parallel_step_stack(heights):
    """Z方向の流路に沿って3B以上同高で続く段の重なりと間隔を測る。"""
    h = heights.astype(int)
    stable = (h[1:-1] == h[:-2]) & (h[1:-1] == h[2:])
    aligned = (h[1:-1, 1:] != h[1:-1, :-1]) & stable[:, 1:] & stable[:, :-1]
    counts = aligned.sum(axis=1)
    spacings, crowded = [], 0
    for row in aligned:
        gaps = np.diff(np.flatnonzero(row))
        spacings.extend(gaps.tolist())
        if len(gaps) >= 3 and np.median(gaps) <= 2:
            crowded += 1
    return dict(maximum=int(counts.max(initial=0)), q90=float(np.quantile(counts, .9)),
                crowded_rows=crowded, median_spacing=float(np.median(spacings)) if spacings else None)


def bank_faces(samples, job, camera, fov, foreground_output=None):
    """撮影下部の可視端面を1画素単位で測る。指定時はその実画素図も保存する。"""
    from collections import defaultdict
    yy, xx = np.mgrid[594:1080, 0:1920]
    rgb, hits = trace(samples, job, camera, fov, 1920, 1080, 1, np.column_stack([xx.ravel(), yy.ravel()]))
    rgb, hits = rgb.reshape(486, 1920, 3), hits.reshape(486, 1920, 5)
    if foreground_output is not None:
        Image.fromarray(rgb).save(foreground_output)
    groups = defaultdict(set)
    wet = (samples['flags'] & 3) > 0
    level = np.where(wet, samples['top'], -128)
    padded = np.pad(level, 2, constant_values=-128)
    protected = np.full(level.shape, -128)
    for dz in range(5):
        for dx in range(5):
            protected = np.maximum(protected, padded[dz:dz+level.shape[0], dx:dx+level.shape[1]])
    x0, z0 = job['bounds'][:2]
    for y in range(486):
        for x in range(1920):
            h = hits[y, x]
            axis = int(h[3])
            if axis in (0, 2) and rgb[y, x, 0] != 48 and h[1] > protected[h[2]-z0, h[0]-x0]+1:
                groups[(axis, int(h[axis]), int(h[1]), int(h[4]))].add((y, x))
    slender = []
    corners = []
    small_corners = []
    longest = 0
    longest_face = None
    parallel = defaultdict(set)
    parallel_faces = []
    for face, pixels in groups.items():
        previous_longest = longest
        world = {int(hits[y, x, 2 if face[0] == 0 else 0]) for y, x in pixels}
        run, previous, face_run = 0, None, 0
        for a in sorted(world):
            run = run+1 if previous is not None and a == previous+1 else 1
            longest = max(longest, run)
            face_run = max(face_run, run)
            previous = a
        if longest > previous_longest:
            longest_face = face
        if face_run >= 8:
            parallel[(face[0], face[3])].add(face[1])
            parallel_faces.append(dict(face=face, length=face_run))
        while pixels:
            start = pixels.pop()
            todo, component = [start], [start]
            while todo:
                y, x = todo.pop()
                for q in [(y-1, x), (y+1, x), (y, x-1), (y, x+1)]:
                    if q in pixels:
                        pixels.remove(q)
                        todo.append(q)
                        component.append(q)
            yy, xx = np.array(component).T
            w, h = int(xx.max()-xx.min()+1), int(yy.max()-yy.min()+1)
            span = int(np.ptp(hits[yy, xx, 2 if face[0]==0 else 0]))+1
            if (span <= 2 and w >= 16 and h >= 32 and len(component) < .65*w*h
                    and xx.min()>0 and xx.max()<1919 and yy.min()>0 and yy.max()<485
                    and _triangle_fraction(yy, xx) > .9):
                corners.append(dict(face=face, width=w, height=h, pixels=len(component), world_span=span,
                                    bounds=[int(xx.min()), int(yy.min()), int(xx.max()), int(yy.max())]))
            if (span <= 2 and w >= 4 and h >= 8 and 16 <= len(component) <= 1024
                    and len(component) < .7*w*h and xx.min()>0 and xx.max()<1919
                    and yy.min()>0 and yy.max()<485 and _triangle_fraction(yy, xx) > .85):
                cx, cz = int(np.median(hits[yy, xx, 0])), int(np.median(hits[yy, xx, 2]))
                ix, iz = cx-x0, cz-z0
                if 2 <= ix < samples.shape[1]-2 and 2 <= iz < samples.shape[0]-2:
                    ground = samples['ground']
                    sx = (int(ground[iz, ix+2])-int(ground[iz, ix-2]))/4
                    sz = (int(ground[iz+2, ix])-int(ground[iz-2, ix]))/4
                    if math.hypot(sx, sz) <= .9:
                        small_corners.append(dict(x=cx, z=cz, pixels=len(component), face=face,
                                                  pixel=[int(np.median(xx)), int(np.median(yy))+594]))
            if h >= 48 and h >= 3*w:
                slender.append(dict(face=face, width=w, height=h, pixels=len(component), bounds=[int(xx.min()), int(yy.min()), int(xx.max()), int(yy.max())], world_span=int(np.ptp(hits[yy, xx, 2 if face[0]==0 else 0]))+1))
    clusters = defaultdict(int)
    for corner in small_corners:
        for ox, oz in [(0,0), (8,0), (0,8), (8,8)]:
            clusters[(ox,oz,(corner['x']+ox)//16,(corner['z']+oz)//16)] += 1
    ix, iz = math.floor(camera['x'])-x0, math.floor(camera['z'])-z0
    clearance = camera['y']+1.62-max(int(samples['ground'][iz, ix]), int(samples['top'][iz, ix]))-1 if 0<=iz<samples.shape[0] and 0<=ix<samples.shape[1] else None
    return dict(eye_clearance=clearance, longest_visible_face=longest, longest_face=longest_face, parallel_planes=max(map(len, parallel.values()), default=0), slender=slender, corners=corners,
                small_corners=small_corners, maximum_corner_cluster=max(clusters.values(), default=0), parallel_faces=parallel_faces,
                measurement_resolution=[1920, 1080], foreground_rows=[594, 1080],
                foreground_coverage=float(np.mean(hits[:, :, 3] >= 0)),
                nearfield_coverage=float(np.mean(hits[216:, :, 3] >= 0)))


def source_parallel_bands(samples, job, camera, fov):
    """源頭の同視点で水際を含む可視縁を横断し、曲がる短い面も一本ずつ数える。"""
    from collections import defaultdict
    rows = np.arange(600, 1021, 20)
    yy, xx = np.meshgrid(rows, np.arange(1920), indexing='ij')
    rgb, hits = trace(samples, job, camera, fov, 1920, 1080, 1,
                      np.column_stack([xx.ravel(), yy.ravel()]))
    hits, rgb = hits.reshape(len(rows), 1920, 5), rgb.reshape(len(rows), 1920, 3)
    counts = {'left': [], 'right': []}
    wet = (samples['flags'] & 3) > 0
    passage = {}
    x0, z0 = job['bounds'][:2]
    faces = {'left': defaultdict(list), 'right': defaultdict(list)}
    for row, y in enumerate(rows):
        h = hits[row]
        valid = (((h[:, 3] == 0) | (h[:, 3] == 2)) & (rgb[row, :, 0] != 48)
                 & (h[:, 0] >= -10244) & (h[:, 0] <= -10200)
                 & (h[:, 2] >= -12810) & (h[:, 2] <= -12740))
        for side in counts:
            mask = valid & ((h[:, 0] < -10220) if side == 'left' else (h[:, 0] > -10220))
            indices = np.flatnonzero(mask)
            runs = np.split(indices, np.flatnonzero(np.diff(indices) > 1)+1)
            runs = [run for run in runs if np.count_nonzero(h[run, 3] == 0) >= 8]
            counts[side].append(len(runs))
            for run in runs:
                for ix in run:
                    point = h[ix]
                    if point[3] != 0:
                        continue
                    key = (int(point[0]), int(point[2]))
                    if key not in passage:
                        px, pz = key[0]-x0, key[1]-z0
                        passage[key] = any(dx*dx+dz*dz <= 9 and 0 <= px+dx < wet.shape[1]
                                           and 0 <= pz+dz < wet.shape[0] and wet[pz+dz, px+dx]
                                           for dz in range(-3, 4) for dx in range(-3, 4))
                    if not passage[key]:
                        faces[side][tuple(map(int, point[[0, 1, 4]]))].append((int(y), int(point[2])))
    measured = {}
    for side in counts:
        long_faces = []
        for face, points in faces[side].items():
            y, z = np.array(points).T
            if np.ptp(y) >= 160 and len(np.unique(y)) >= 7 and np.ptp(z) >= 3:
                long_faces.append(dict(face=list(face), screen_height=int(np.ptp(y)),
                                       scan_rows=len(np.unique(y)), longitudinal_blocks=int(np.ptp(z))+1))
        measured[side] = dict(maximum=max(counts[side]), crowded_rows=sum(n > 2 for n in counts[side]),
                              counts=counts[side], long_faces=long_faces)
    return dict(scan_rows=rows.tolist(), banks=measured)


def validate_source_bands(measured):
    """保護した水際の一本を許容し、多重の帯と外側の長い平行面を拒否する。"""
    for side, bank in measured['banks'].items():
        if bank['maximum'] > 3 or bank['crowded_rows'] > 4 or bank['long_faces']:
            raise ValueError('源頭の'+side+'に水路と平行な可視帯が残っています')


def validate_bank_faces(measured, parallel=False, thin=False, straight=False, corners=False, dots=False, steps=None):
    """bank_facesの結果を検査し、範囲不足・視点埋没・指定した形状の不合格でValueErrorを投げる。"""
    if measured['eye_clearance'] is None or measured['nearfield_coverage'] < .95:
        raise ValueError('前景の実列範囲が不足しています')
    if measured['eye_clearance'] <= .25:
        raise ValueError('視点が地表に埋もれているか近すぎます')
    if parallel and measured['parallel_planes'] > 2:
        raise ValueError('8B以上続く平行な同高端面が3面以上あります')
    if straight and measured['longest_visible_face'] > 10:
        raise ValueError('同じ高さの可視端面が10Bを超えて直線で続きます')
    if thin and measured['slender']:
        raise ValueError('前景に細長く投影される段の端面があります')
    if corners and max((c['pixels'] for c in measured['corners']), default=0) > 1024:
        raise ValueError('前景に大きな三角形の段の角があります')
    if dots and measured['maximum_corner_cluster'] > 8:
        raise ValueError('緩斜面の16B四方に小さな三角の角が密集しています')
    if steps is not None and (steps['maximum'] > 5 or steps['crowded_rows'] > 3):
        raise ValueError('水路に沿った短い段が平行に重なっています')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--samples', type=Path, required=True)
    parser.add_argument('--job', required=True)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--point', required=True)
    parser.add_argument('--effective-fov', type=float, required=True,
                        help='設定値ではなく飛行などの補正後の校正値')
    parser.add_argument('--pixel-step', type=int, choices=[1, 2, 4], default=1)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--hits', type=Path)
    parser.add_argument('--bank-face-metrics', action='store_true',
                        help='同高端面の連続長と細長い投影面を検査する')
    parser.add_argument('--reject-parallel-shelves', action='store_true')
    parser.add_argument('--reject-thin-faces', action='store_true')
    parser.add_argument('--reject-long-faces', action='store_true')
    parser.add_argument('--reject-corner-faces', action='store_true')
    parser.add_argument('--reject-dot-clusters', action='store_true')
    parser.add_argument('--reject-source-bands', action='store_true', help='01-sourceの同視点で見える段の重なりを検査する')
    parser.add_argument('--parallel-stack-bounds', type=int, nargs=4, metavar=('X0', 'Z0', 'X1', 'Z1'))
    args = parser.parse_args()
    request = json.loads((args.samples/'request.json').read_text(encoding='utf-8'))
    job = next(j for j in request['jobs'] if j['name'] == args.job)
    plan = json.loads(args.plan.read_text(encoding='utf-8'))
    camera = next(p for p in plan['points'] if p['name'] == args.point)
    data, _ = read_samples(args.samples, job)
    width, height = plan['view']['width'], plan['view']['height']
    rgb, hits = trace(data, job, camera, args.effective_fov, width, height, args.pixel_step)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(rgb).resize((width, height), Image.Resampling.NEAREST).save(args.output)
    if args.hits:
        np.savez_compressed(args.hits, hits=hits)
    metadata = dict(manifest_sha256=request['manifest_sha256'], job=job, camera=camera,
                    eye_height=1.62, nominal_fov=plan['view']['fov'], effective_fov=args.effective_fov,
                    pixel_step=args.pixel_step, resolution=[width, height],
                    note='地表以下を充填した1B高さ場。内部空洞は別の列検査で照合する。水は不透明。材質・霧・粒子は再現しない。')
    if args.bank_face_metrics or args.reject_parallel_shelves or args.reject_thin_faces or args.reject_long_faces or args.reject_corner_faces or args.reject_dot_clusters or args.reject_source_bands or args.parallel_stack_bounds:
        metadata['bank_faces'] = bank_faces(data, job, camera, args.effective_fov, args.output.with_name(args.output.stem+'-foreground.png'))
    if args.parallel_stack_bounds:
        x0, z0, x1, z1 = args.parallel_stack_bounds
        bx, bz, ex, ez = job['bounds']
        if job['step'] != 1 or not (bx <= x0 < x1 <= ex and bz <= z0 < z1 <= ez):
            raise ValueError('平行段の検査には範囲内の1B実列が必要です')
        metadata['parallel_steps'] = parallel_step_stack(data['ground'][z0-bz:z1-bz, x0-bx:x1-bx])
    if args.reject_source_bands:
        metadata['source_parallel_bands'] = source_parallel_bands(data, job, camera, args.effective_fov)
    args.output.with_suffix('.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding='utf-8')
    if args.reject_parallel_shelves or args.reject_thin_faces or args.reject_long_faces or args.reject_corner_faces or args.reject_dot_clusters or args.reject_source_bands or args.parallel_stack_bounds:
        validate_bank_faces(metadata['bank_faces'], args.reject_parallel_shelves, args.reject_thin_faces, args.reject_long_faces, args.reject_corner_faces,
                            dots=args.reject_dot_clusters, steps=metadata.get('parallel_steps'))

    if args.reject_source_bands:
        validate_source_bands(metadata['source_parallel_bands'])


if __name__ == '__main__':
    main()
