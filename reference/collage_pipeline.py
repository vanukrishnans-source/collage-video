"""Collage Video reference pipeline (desktop Python; the Kotlin app mirrors it stage by stage).

The collage people become the people of the reference video: the reference supplies motion, body pose,
camera and timing; the collage supplies who they are.

Per collage person (faces in reading order): ArcFace identity of the face; colour statistics (CIE Lab mean/std)
of their hair, skin and upper-body clothes, measured with MediaPipe's multiclass selfie segmenter in a
face-centred crop.
Reference video: pass 1 = face detection + tracking (Face Swap Video tracker / smoother) + MediaPipe
PoseLandmarker (instance masks) + multiclass segmentation on sampled frames -> colour statistics of each
performer's hair / skin / upper / lower clothes. Each performer gets a collage person (left to right, Flip).
Pass 2, every frame: pose instance masks + segmentation per performer -> recolour hair, skin, outfit to the
collage person's (Reinhard transfer in Lab with clamped contrast, keeps the reference lighting/shading/folds),
then inswapper identity swap of the face (+ optional GPEN-256) -> H.264.
"""
import os, sys, json, math, subprocess, time
import numpy as np, cv2
FS = '/workspace/faceswap-app/test'; sys.path.insert(0, FS)
import faceswap_pipeline as fp
fp.MODEL = '/workspace/collage-video-app/core/src/main/assets/face_landmarker.task'
import ai_pipeline as ap
import video_pipeline as vp
import mediapipe as mp
from mediapipe.tasks.python import vision, BaseOptions

M = os.environ.get('CV_MODELS', '/workspace/collage-video-app/models_build/')
HAIR, BODY, FACE, CLOTHES, OTHERS = 1, 2, 3, 4, 5
REGIONS = ['hair', 'skin', 'upper', 'lower']
STRENGTH = {'hair': 0.9, 'skin': 0.75, 'upper': 1.0, 'lower': 1.0}
GAIN = {'L': (0.6, 1.4), 'ab': (0.5, 1.5)}
SAME_GARMENT_DE = 12.0
GATE = (3.0, 5.0)   # Mahalanobis distance to the performer's own region colours: full recolour below, none above
EXPOSURE = (0.25, 1.25)

_pose = _seg = None
def pose_model():
    global _pose
    if _pose is None:
        _pose = vision.PoseLandmarker.create_from_options(vision.PoseLandmarkerOptions(
            base_options=BaseOptions(model_asset_path=M + 'pose_landmarker_full.task'), running_mode=vision.RunningMode.IMAGE,
            num_poses=6, output_segmentation_masks=True, min_pose_detection_confidence=0.4, min_pose_presence_confidence=0.4))
    return _pose
def seg_model():
    global _seg
    if _seg is None:
        _seg = vision.ImageSegmenter.create_from_options(vision.ImageSegmenterOptions(
            base_options=BaseOptions(model_asset_path=M + 'selfie_multiclass_256x256.tflite'), running_mode=vision.RunningMode.IMAGE,
            output_confidence_masks=True, output_category_mask=False))
    return _seg

def to_lab(rgb_u8):
    return cv2.cvtColor(rgb_u8.astype(np.float32) / 255.0, cv2.COLOR_RGB2Lab)
def from_lab(lab):
    return np.clip(cv2.cvtColor(lab, cv2.COLOR_Lab2RGB) * 255.0 + 0.5, 0, 255).astype(np.uint8)

def segment(rgb, x0, y0, x1, y1):
    """Multiclass confidences (6, h, w) for the crop rgb[y0:y1, x0:x1] (any size; the model runs at 256)."""
    crop = np.ascontiguousarray(rgb[y0:y1, x0:x1])
    r = seg_model().segment(mp.Image(image_format=mp.ImageFormat.SRGB, data=crop))
    h, w = crop.shape[:2]
    return np.stack([cv2.resize(m.numpy_view().astype(np.float32).reshape(m.height, m.width), (w, h), interpolation=cv2.INTER_LINEAR)
                     for m in r.confidence_masks])

class Stats:
    """Weighted Lab moments."""
    def __init__(self): self.w = 0.0; self.s = np.zeros(3); self.q = np.zeros(3)
    def add(self, lab, wgt):
        m = wgt > 0.5
        if m.sum() < 20: return
        v = lab[m]; self.w += len(v); self.s += v.sum(0); self.q += (v.astype(np.float64) ** 2).sum(0)
    def merge(self, o): r = Stats(); r.w = self.w + o.w; r.s = self.s + o.s; r.q = self.q + o.q; return r
    @property
    def ok(self): return self.w >= 200
    def mean(self): return self.s / max(self.w, 1)
    def std(self): return np.sqrt(np.maximum(self.q / max(self.w, 1) - self.mean() ** 2, 1.0))
    def __repr__(self): return f'Stats(n={int(self.w)}, mean={np.round(self.mean(),1)}, std={np.round(self.std(),1)})'

# ------------------------------------------------------------------ collage
def analyse_collage(img_bgr, faces):
    rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB); H, W = rgb.shape[:2]; lab = to_lab(rgb)
    cs = [f[:, :2].mean(0) for f in faces]; ws = [float(np.ptp(f[:, 0])) for f in faces]
    yy, xx = np.mgrid[0:H, 0:W]
    # Voronoi ownership (vertical distance counts half: bodies hang below faces)
    d = np.stack([np.hypot(xx - c[0], (yy - c[1]) * 0.5) for c in cs])
    owner = d.argmin(0)
    out = []
    for i, (f, c, w) in enumerate(zip(faces, cs, ws)):
        side = 4.5 * w
        x0 = int(max(0, c[0] - side / 2)); x1 = int(min(W, c[0] + side / 2))
        y0 = int(max(0, c[1] - side * 0.4)); y1 = int(min(H, c[1] + side * 0.75))
        conf = segment(rgb, x0, y0, x1, y1)
        own = (owner[y0:y1, x0:x1] == i).astype(np.float32)
        sub = lab[y0:y1, x0:x1]; Y = yy[y0:y1, x0:x1]
        nose_y = f[1, 1]; chin_y = f[152, 1]
        st = {r: Stats() for r in REGIONS}
        st['hair'].add(sub, conf[HAIR] * own)
        st['skin'].add(sub, (conf[FACE] * (Y < nose_y) + conf[BODY]) * own)
        st['upper'].add(sub, conf[CLOTHES] * own * (Y > chin_y))
        out.append(st)
    return out

# ------------------------------------------------------------------ reference performers
def pose_instances(rgb):
    r = pose_model().detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(rgb)))
    H, W = rgb.shape[:2]; inst = []
    for lm, m in zip(r.pose_landmarks, r.segmentation_masks or []):
        mm = m.numpy_view().reshape(H, W).astype(np.float32)
        ys, xs = np.where(mm > 0.5)
        if len(xs) < 200: continue
        pts = np.array([[l.x * W, l.y * H, l.visibility] for l in lm], np.float64)
        head = pts[:11][pts[:11, 2] > 0.3]
        hip = pts[[23, 24]]; hip_y = hip[:, 1].mean() if (hip[:, 2] > 0.3).all() else ys.min() + 0.55 * (ys.max() - ys.min())
        inst.append(dict(mask=mm, box=(xs.min(), ys.min(), xs.max() + 1, ys.max() + 1), pts=pts,
                         head=head[:, :2].mean(0) if len(head) else None, hip_y=hip_y))
    if inst:
        # every pixel belongs to the instance with the highest mask confidence (arms crossing into the other
        # person's box, held hands); low-confidence edges still count, softly
        allm = np.stack([it['mask'] for it in inst]); top = allm.max(0)
        for it in inst: it['mask'] = np.where(it['mask'] >= top, smoothstep(it['mask'], 0.15, 0.45), 0.0).astype(np.float32)
    return inst

def head_crop(it, W, H):
    """Square crop around the head of a pose instance (for finding small faces), or None."""
    pts = it['pts']; head = pts[:11][pts[:11, 2] > 0.3]
    if len(head) < 3: return None
    c = head[:, :2].mean(0)
    sh = pts[[11, 12]]
    size = max(float(np.ptp(head[:, 0])) * 3.0, float(np.hypot(*(sh[0, :2] - sh[1, :2]))) * 1.6 if (sh[:, 2] > 0.3).all() else 0.0, 48.0)
    x0 = int(max(0, c[0] - size / 2)); y0 = int(max(0, c[1] - size / 2)); x1 = int(min(W, c[0] + size / 2)); y1 = int(min(H, c[1] + size / 2))
    if x1 - x0 < 16 or y1 - y0 < 16: return None
    return x0, y0, x1, y1

def detect_faces(frame_bgr, insts):
    """Faces: full frame + one targeted scan around every pose instance's head (small faces in full-body shots)."""
    H, W = frame_bgr.shape[:2]
    if fp._landmarker is None: fp.detect(np.zeros((64, 64, 3), np.uint8))
    faces = [f[:468] for f in fp._detect_region(frame_bgr, 0, 0, W, H)]
    for it in insts:
        hc = head_crop(it, W, H)
        if hc is None: continue
        x0, y0, x1, y1 = hc
        for f in fp._detect_region(frame_bgr, x0, y0, x1 - x0, y1 - y0): fp._merge_face(faces, f[:468])
    return vp.dedupe([f.astype(np.float32) for f in faces])

def instance_regions(rgb, it):
    """Soft region weights (dict region -> (h,w)) inside the crop around the instance, plus crop coords."""
    H, W = rgb.shape[:2]; x0, y0, x1, y1 = it['box']
    pw, ph = x1 - x0, y1 - y0; s = max(pw, ph) * 1.1; cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    X0 = int(max(0, cx - s / 2)); Y0 = int(max(0, cy - s / 2)); X1 = int(min(W, cx + s / 2)); Y1 = int(min(H, cy + s / 2))
    conf = segment(rgb, X0, Y0, X1, Y1)
    inst = it['mask'][Y0:Y1, X0:X1]
    Y = np.mgrid[Y0:Y1, X0:X1][0]
    upper = Y < it['hip_y']
    reg = {'hair': conf[HAIR] * inst, 'skin': (conf[FACE] + conf[BODY]) * inst,
           'upper': conf[CLOTHES] * inst * upper, 'lower': conf[CLOTHES] * inst * (~upper)}
    return reg, (X0, Y0, X1, Y1)

def match_instances(insts, faces_at, prev_boxes):
    """faces_at: {person: (centre, width)} at this frame. prev_boxes: {person: box}. Returns {inst_idx: person}."""
    cand = []
    for j, it in enumerate(insts):
        for p, (c, w) in faces_at.items():
            if it['head'] is not None:
                d = np.hypot(*(it['head'] - c)) / max(w, 1.0)
                if d < 2.0: cand.append((d, j, p))
    cand.sort(); used_j = set(); used_p = set(); out = {}
    for d, j, p in cand:
        if j in used_j or p in used_p: continue
        used_j.add(j); used_p.add(p); out[j] = p
    # no face this frame (turned away / missed): keep following the box of the last frame
    cand = []
    for j, it in enumerate(insts):
        if j in used_j: continue
        for p, b in prev_boxes.items():
            if p in used_p: continue
            s = vp.iou(tuple(map(float, it['box'])), tuple(map(float, b)))
            if s > 0.3: cand.append((-s, j, p))
    cand.sort()
    for s, j, p in cand:
        if j in used_j or p in used_p: continue
        used_j.add(j); used_p.add(p); out[j] = p
    return out

def exposure(cs, ps):
    """Scene exposure relative to the collage photo, anchored on skin (both people have skin in view):
    e = L(performer skin) / L(collage skin), clamped. Recoloured regions get the collage colour at this exposure,
    so a maroon top in a backlit sunset stays a dark maroon top instead of glowing."""
    if not (cs['skin'].ok and ps['skin'].ok): return 1.0
    return float(np.clip(ps['skin'].mean()[0] / max(cs['skin'].mean()[0], 1.0), EXPOSURE[0], EXPOSURE[1]))

def transfer_params(src, tgt, e=1.0, keep_l=False):
    """Per-channel Lab (gain, offset) mapping the performer's region stats onto the collage person's, at exposure e.
    keep_l: keep the performer's lightness (skin = exposure anchor), transfer only the colour."""
    ms, ss, mt, st = src.mean().copy(), src.std().copy(), tgt.mean(), tgt.std()
    ms[0] *= e; ss[0] *= e
    if not keep_l: ms[1:] *= math.sqrt(e); ss[1:] *= math.sqrt(e)
    g = ss / st
    g[0] = np.clip(g[0], *GAIN['L']); g[1:] = np.clip(g[1:], *GAIN['ab'])
    o = ms - g * mt
    if keep_l: g[0] = 1.0; o[0] = 0.0
    return g.astype(np.float32), o.astype(np.float32), mt.astype(np.float32), st.astype(np.float32)

def smoothstep(x, a, b):
    t = np.clip((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t)

def recolour(rgb, reg, crop, params):
    X0, Y0, X1, Y1 = crop
    sub = rgb[Y0:Y1, X0:X1]; lab = to_lab(sub); out = lab.copy(); acc = np.zeros(lab.shape[:2], np.float32)
    for r, (g, o, mt, st) in params.items():
        w = cv2.GaussianBlur(smoothstep(reg[r], 0.3, 0.6), (0, 0), 1.0) * STRENGTH[r]
        # colour gate: pixels that don't look like this performer's own hair/skin/outfit (e.g. the other person's
        # sleeve claimed by the wrong mask) are left alone
        d = np.sqrt((((lab - mt) / st) ** 2).sum(-1))
        w = w * (1.0 - smoothstep(d, GATE[0], GATE[1]))
        w = np.minimum(w, 1.0 - acc); acc += w
        out = out + w[..., None] * ((lab * g + o) - lab)
    rgb[Y0:Y1, X0:X1] = from_lab(out)

# ------------------------------------------------------------------ job
def pair_performers(tracks, n_src, flip):
    """Mirror of Kotlin People.layout + autoAssign (single-track people): biggest n_src of the people visible
    together, left to right, size and position averaged over all frames where they are all visible."""
    assign = [-1] * len(tracks)
    if not tracks or n_src <= 0: return assign, -1
    nf = max(max(t) for t in tracks) + 1
    solid = min(max(3, nf // 4), 8)
    major = [k for k, t in enumerate(tracks) if len(t) >= solid] or list(range(len(tracks)))
    kf = max(range(nf), key=lambda f: (sum(1 for k in major if f in tracks[k]), -f))
    vis = [k for k in major if kf in tracks[k]]
    co = [f for f in range(nf) if all(f in tracks[k] for k in vis)] or [kf]
    wd = {k: np.mean([np.ptp(tracks[k][f][:, 0]) for f in co]) for k in vis}
    cx = {k: np.mean([tracks[k][f][:, 0].mean() for f in co]) for k in vis}
    main = sorted(sorted(vis, key=lambda k: -wd[k])[:max(1, n_src)], key=lambda k: cx[k])
    for i, k in enumerate(main): assign[k] = (i + flip) % n_src
    return assign, kf

def box_pts(b): return np.array([[b[0], b[1]], [b[2], b[3]]], np.float64)

def run(video, collage, out_mp4, start=0.0, end=None, fps=15.0, flip=0, enhance='gpen256', face=True, colours=True, log=print, sheet=None, max_short=720):
    t00 = time.perf_counter()
    cap = cv2.VideoCapture(video)
    src_fps = cap.get(cv2.CAP_PROP_FPS); n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    w, h = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    duration = n / src_fps; end = min(end or duration, duration); fps = min(fps, src_fps)
    W, H, s = vp.out_size(w, h, max_short)
    sel = vp.frame_times(duration, src_fps, start, end, fps); selset = {i: k for k, i in enumerate(sel)}
    log(f'{w}x{h} {src_fps:.2f}fps {duration:.2f}s -> {W}x{H}, {len(sel)} frames @ {fps} fps')
    col = ap.load(collage)
    cf = [f[:468].astype(np.float32) for f in ap.detect(col)]
    cf.sort(key=lambda f: (round(f[:, 1].mean() / max(np.ptp(f[:, 1]), 1) / 2), f[:, 0].mean()))   # reading order
    log(f'collage faces: {len(cf)} at x {[int(f[:, 0].mean()) for f in cf]}')
    cstats = analyse_collage(col, cf)
    for i, st in enumerate(cstats): log(f'  collage person {i + 1}: ' + ', '.join(f'{k}={v}' for k, v in st.items() if v.ok))
    step = max(1, int(round(fps * 0.5)))
    # ---- pass 1: pose instances + faces every frame, colour statistics on sampled frames
    pdets = []; fdets = []; heads = []; samples = {}; i = 0; t0 = time.perf_counter()
    while True:
        ok, fr = cap.read()
        if not ok or i > sel[-1]: break
        if i in selset:
            k = len(pdets); frame = vp.prep(fr, W, H, s)
            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB); insts = pose_instances(rgb)
            pdets.append([box_pts(it['box']) for it in insts]); heads.append([it['head'] for it in insts])
            fdets.append(detect_faces(frame, insts))
            if k % step == 0:
                lab = to_lab(rgb); per = []
                for it in insts:
                    reg, (X0, Y0, X1, Y1) = instance_regions(rgb, it)
                    st = {r: Stats() for r in REGIONS}
                    for r in REGIONS: st[r].add(lab[Y0:Y1, X0:X1], reg[r])
                    per.append(st)
                samples[k] = per
        i += 1
    t_p1 = time.perf_counter() - t0
    nf = len(pdets)
    # performers = tracked pose instances; collage people go to performers left to right (Flip rotates)
    ptracks = vp.track(pdets, max_gap=int(round(fps)))
    passign, pf = pair_performers(ptracks, len(cf), flip)
    def det_index(t, k):
        b = ptracks[t].get(k)
        if b is None: return -1
        for j, d in enumerate(pdets[k]):
            if np.array_equal(d, b): return j
        return -1
    log(f'performers {len(ptracks)} lengths {[len(t) for t in ptracks]} collage person per performer {passign} (frame {pf})')
    # faces: tracked + smoothed (Face Swap Video), each face track belongs to the performer whose head it sits on
    ftracks = vp.track(fdets, max_gap=int(round(fps)))
    fsm = [vp.smooth_track(t, fps) for t in ftracks]
    fowner = []
    for t in ftracks:
        votes = {}
        for k, pts in t.items():
            c = pts.mean(0); fw = float(np.ptp(pts[:, 0])); best = None
            for pt in range(len(ptracks)):
                j = det_index(pt, k)
                if j < 0 or heads[k][j] is None: continue
                d = float(np.hypot(*(heads[k][j] - c))) / max(fw, 1.0)
                if d < 2.0 and (best is None or d < best[0]): best = (d, pt)
            if best: votes[best[1]] = votes.get(best[1], 0) + 1
        fowner.append(max(votes, key=votes.get) if votes else -1)
    fassign = [passign[o] if o >= 0 else -1 for o in fowner]
    log(f'face tracks {len(ftracks)} lengths {[len(t) for t in ftracks]} owner performer {fowner} -> collage person {fassign}; pass 1 {t_p1:.1f}s')
    # ---- performer colour statistics -> transfer parameters
    params = {}
    for pt, a in enumerate(passign):
        if a < 0: continue
        ps = {r: Stats() for r in REGIONS}
        for k, per in samples.items():
            j = det_index(pt, k)
            if j >= 0:
                for r in REGIONS: ps[r] = ps[r].merge(per[j][r])
        cs = cstats[a]; pr = {}; e = exposure(cs, ps)
        log(f'  performer {pt} -> collage person {a + 1}: ' + ', '.join(f'{k}={v}' for k, v in ps.items() if v.ok))
        log(f'    exposure vs collage: {e:.2f}')
        if cs['hair'].ok and ps['hair'].ok: pr['hair'] = transfer_params(cs['hair'], ps['hair'], e)
        if cs['skin'].ok and ps['skin'].ok: pr['skin'] = transfer_params(cs['skin'], ps['skin'], e, keep_l=True)
        if cs['upper'].ok and ps['upper'].ok:
            same = ps['lower'].ok and float(np.linalg.norm(ps['upper'].mean() - ps['lower'].mean())) < SAME_GARMENT_DE
            if same:
                tgt = ps['upper'].merge(ps['lower']); pr['upper'] = pr['lower'] = transfer_params(cs['upper'], tgt, e)
            else:
                pr['upper'] = transfer_params(cs['upper'], ps['upper'], e)
            log(f'    outfit: {"one garment -> whole outfit recoloured" if same else "top recoloured, lower clothes kept (not in the collage)"}')
        params[pt] = pr
    lat = {}
    for a in set(x for x in fassign if x >= 0):
        emb, _ = ap.embedding(col, ap.kps5(cf[a])); lat[a] = ap.latent_for(emb)
    # ---- pass 2
    cap = cv2.VideoCapture(video); i = 0; k = 0; last = {}
    enc = subprocess.Popen(['ffmpeg', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{W}x{H}', '-r', str(fps),
                            '-i', '-', '-c:v', 'libx264', '-preset', 'medium', '-crf', '18', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', out_mp4],
                           stdin=subprocess.PIPE)
    t0 = time.perf_counter(); keep = {}
    sheet_k = set(np.linspace(0, len(sel) - 1, 8).astype(int).tolist())
    while True:
        ok, fr = cap.read()
        if not ok or i > sel[-1]: break
        if i in selset:
            rgb = cv2.cvtColor(vp.prep(fr, W, H, s), cv2.COLOR_BGR2RGB); orig = rgb.copy()
            if colours:
                insts = pose_instances(rgb)
                for pt, a in enumerate(passign):
                    if a < 0 or not params.get(pt): continue
                    b = ptracks[pt].get(k); it = None
                    if b is not None:
                        bb = (b[0, 0], b[0, 1], b[1, 0], b[1, 1])
                        sc = [vp.iou(tuple(map(float, x['box'])), bb) for x in insts]
                        if sc and max(sc) > 0.5: it = insts[int(np.argmax(sc))]
                    if it is not None: last[pt] = (k, it)
                    elif pt in last and k - last[pt][0] <= 3: it = last[pt][1]      # brief drop-out: reuse the last mask
                    if it is None: continue
                    reg, crop = instance_regions(rgb, it); recolour(rgb, reg, crop, params[pt])
            if face:
                bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
                for ti, a in enumerate(fassign):
                    if a >= 0 and k in fsm[ti]:
                        pts = fsm[ti][k].astype(np.float32)
                        bgr = ap.swap_face(bgr, pts, lat[a])
                        if enhance: bgr = ap.enhance(bgr, pts, enhance, 0.8)
                rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
            if k in sheet_k: keep[k] = (orig, rgb.copy())
            enc.stdin.write(rgb.tobytes()); k += 1
            if k % 25 == 0: log(f'  frame {k}/{len(sel)}  ({(time.perf_counter() - t0) / k:.2f}s/frame)')
        i += 1
    enc.stdin.close(); enc.wait()
    t_p2 = time.perf_counter() - t0
    if sheet:
        rows = [np.hstack([a, b]) for a, b in (keep[x] for x in sorted(keep))]
        sh = np.vstack(rows); sh = cv2.resize(sh, (sh.shape[1] // 2, sh.shape[0] // 2), interpolation=cv2.INTER_AREA)
        cv2.imwrite(sheet, cv2.cvtColor(sh, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 88])
    st = dict(frames=len(sel), W=W, H=H, fps=fps, performers=len(ptracks), collage_person_per_performer=passign,
              face_tracks=len(ftracks), face_assign=fassign, pass1_s=round(t_p1, 1), pass2_s=round(t_p2, 1),
              per_frame_s=round(t_p2 / max(1, len(sel)), 3), total_s=round(time.perf_counter() - t00, 1))
    log(json.dumps(st)); return st

if __name__ == '__main__':
    import argparse
    p = argparse.ArgumentParser()
    p.add_argument('video'); p.add_argument('collage'); p.add_argument('out')
    p.add_argument('--start', type=float, default=0); p.add_argument('--end', type=float, default=None)
    p.add_argument('--fps', type=float, default=15); p.add_argument('--flip', type=int, default=0)
    p.add_argument('--enhance', default='gpen256'); p.add_argument('--no-face', action='store_true'); p.add_argument('--no-colours', action='store_true')
    p.add_argument('--sheet', default=None)
    a = p.parse_args()
    run(a.video, a.collage, a.out, a.start, a.end, a.fps, a.flip, None if a.enhance == 'none' else a.enhance, not a.no_face, not a.no_colours, sheet=a.sheet)
