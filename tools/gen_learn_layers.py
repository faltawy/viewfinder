"""
Splits two of the Learn photos into layers, so the playgrounds can blur parts of a real photo the way a lens would.

  p447 (a man on a lake wall): the man and the wall he stands on (sharp at every aperture) and a plate of the
  lake and mountains with him painted out (blurred more as the aperture opens).
  p88 (traffic from above): the cars cut out, and the road with the cars painted out. A long exposure draws the
  cars smeared along the road over the empty road, which is what a real one records.

Run once after changing the photos; the outputs are committed under app/src/main/assets/learn/.

  python3 -m venv /tmp/vfenv && /tmp/vfenv/bin/pip install numpy opencv-python-headless
  /tmp/vfenv/bin/python tools/gen_learn_layers.py app/src/main/assets/learn
"""
import sys
from pathlib import Path

import cv2
import numpy as np


def feather(mask: np.ndarray, px: int) -> np.ndarray:
    return cv2.GaussianBlur(mask.astype(np.float32), (0, 0), px) if px else mask.astype(np.float32)


def rgba(img: np.ndarray, alpha: np.ndarray) -> np.ndarray:
    out = cv2.cvtColor(img, cv2.COLOR_BGR2BGRA)
    out[..., 3] = np.clip(alpha * 255, 0, 255).astype(np.uint8)
    return out


def lake(root: Path) -> None:
    img = cv2.imread(str(root / "p447.jpg"))
    h, w = img.shape[:2]
    # GrabCut from a box around the man (read off a gridded copy of the photo).
    rect = (int(0.445 * w), int(0.415 * h), int(0.10 * w), int(0.465 * h))
    mask = np.zeros((h, w), np.uint8)
    bgd, fgd = np.zeros((1, 65), np.float64), np.zeros((1, 65), np.float64)
    cv2.grabCut(img, mask, rect, bgd, fgd, 8, cv2.GC_INIT_WITH_RECT)
    man = np.isin(mask, (cv2.GC_FGD, cv2.GC_PR_FGD)).astype(np.uint8)
    # GrabCut loses his legs against the dark water; his clothes are near-black, so add the dark pixels in the box.
    x, y, bw, bh = rect
    box = np.zeros((h, w), np.uint8)
    box[y:y + bh, x:x + bw] = 1
    luma = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    man = ((man > 0) | ((luma < 55) & (box > 0))).astype(np.uint8)
    # Join the torso to the legs across the white hem, then drop stray specks of dark water.
    man = cv2.morphologyEx(man, cv2.MORPH_CLOSE, np.ones((15, 7), np.uint8))
    n, labels, stats, _ = cv2.connectedComponentsWithStats(man)
    man = np.isin(labels, [i for i in range(1, n) if stats[i, cv2.CC_STAT_AREA] > 400]).astype(np.uint8)
    # The wall runs across the frame at his distance, so it stays sharp with him.
    wall_top = int(0.862 * h)
    wall = np.zeros((h, w), np.float32)
    wall[wall_top:, :] = 1
    subject = np.maximum(feather(man, 1.2), feather(wall, 2))
    cv2.imwrite(str(root / "p447_subject.png"), rgba(img, subject), [cv2.IMWRITE_PNG_COMPRESSION, 9])
    hole = cv2.dilate(man, np.ones((15, 15), np.uint8))
    plate = cv2.inpaint(img, hole, 9, cv2.INPAINT_TELEA)
    cv2.imwrite(str(root / "p447_plate.jpg"), plate, [cv2.IMWRITE_JPEG_QUALITY, 88])
    cv2.imwrite("/tmp/p447_mask.png", (subject * 255).astype(np.uint8))


# Every car (and motorbike) with its shadow, in pixels of the 1200×800 photo, read off a gridded copy.
CARS = [
    (340, 5, 365, 58), (365, 0, 415, 62), (432, 25, 477, 95), (437, 0, 480, 20), (358, 85, 408, 148),
    (418, 135, 468, 205), (333, 210, 388, 280), (318, 272, 370, 365), (406, 288, 456, 362), (676, 90, 724, 147),
    (740, 60, 805, 145), (598, 126, 712, 268), (694, 290, 752, 362), (803, 2, 826, 32), (745, 382, 812, 462),
    (386, 408, 440, 520), (272, 466, 348, 572), (375, 518, 442, 627), (703, 486, 772, 587), (648, 628, 728, 737),
    (898, 632, 1002, 742), (198, 668, 298, 800), (326, 652, 412, 782),
]


def traffic(root: Path) -> None:
    img = cv2.imread(str(root / "p88.jpg"))
    h, w = img.shape[:2]
    # Seen from above a car is a rounded box; the asphalt caught in its corners smears into asphalt, unseen.
    # The sun is high on the upper left, so each box reaches a little right and down to take the shadow too.
    keep = np.zeros((h, w), np.uint8)
    for x0, y0, x1, y1 in CARS:
        x1, y1, r = min(x1 + 16, w - 1), min(y1 + 28, h - 1), 8
        cv2.rectangle(keep, (x0 + r, y0), (x1 - r, y1), 1, -1)
        cv2.rectangle(keep, (x0, y0 + r), (x1, y1 - r), 1, -1)
        for cx, cy in ((x0 + r, y0 + r), (x1 - r, y0 + r), (x0 + r, y1 - r), (x1 - r, y1 - r)):
            cv2.circle(keep, (cx, cy), r, 1, -1)
    keep = cv2.dilate(keep, np.ones((3, 3), np.uint8))
    cv2.imwrite(str(root / "p88_cars.png"), rgba(img, feather(keep, 1.5)), [cv2.IMWRITE_PNG_COMPRESSION, 9])
    # The empty road: each car's box is filled with the stretch of road just above or below it in the same
    # lane (lanes run straight down the frame), Poisson-blended so its tone meets the asphalt around it.
    # In the busy lanes there is no clean road next to a car, so the boxes are filled in passes, each pass
    # free to copy from road a previous pass has already cleared.
    plate = img.copy()
    boxes = [(max(x0 - 6, 0), max(y0 - 6, 0), min(x1 + 22, w - 1), min(y1 + 34, h - 1)) for x0, y0, x1, y1 in CARS]
    dirty = set(range(len(boxes)))

    def blocked(y0: int, y1: int, x0: int, x1: int) -> bool:
        return any(b[0] < x1 and x0 < b[2] and b[1] < y1 and y0 < b[3] for b in (boxes[i] for i in dirty))

    while dirty:
        progress = False
        for i in sorted(dirty):
            x0, y0, x1, y1 = boxes[i]
            bh = y1 - y0
            shifts = [s * (bh + g) for g in (4, 30, 60, 100, 150, 220, 300) for s in (-1, 1)]
            source = next((d for d in shifts if 0 <= y0 + d and y1 + d < h and not blocked(y0 + d, y1 + d, x0, x1)), None)
            if source is None:
                continue
            patch = np.zeros_like(img)
            patch[y0:y1, x0:x1] = plate[y0 + source:y1 + source, x0:x1]
            hole = np.zeros((h, w), np.uint8)
            hole[y0:y1, x0:x1] = 255
            plate = cv2.seamlessClone(patch, plate, hole, ((x0 + x1) // 2, (y0 + y1) // 2), cv2.NORMAL_CLONE)
            dirty.discard(i)
            progress = True
        if not progress:
            break
    # Bumper-to-bumper lanes have no clean road at all: those boxes take, pixel by pixel, the median of the
    # visible road above and below in the same column, blended in the same way.
    hidden = np.zeros((h, w), bool)
    for i in dirty:
        x0, y0, x1, y1 = boxes[i]
        hidden[y0:y1, x0:x1] = True
    for i in sorted(dirty):
        x0, y0, x1, y1 = boxes[i]
        patch = np.zeros_like(img)
        for x in range(x0, x1):
            lo, hi = max(0, y0 - 160), min(h, y1 + 160)
            seen = ~hidden[lo:hi, x]
            if seen.any():
                patch[y0:y1, x] = np.median(plate[lo:hi, x][seen], axis=0)
        # A little of the asphalt's grain back, so the fill isn't flat plastic.
        grain = np.random.default_rng(i).normal(0, 3.5, patch[y0:y1, x0:x1].shape)
        patch[y0:y1, x0:x1] = np.clip(patch[y0:y1, x0:x1] + grain, 0, 255)
        hole = np.zeros((h, w), np.uint8)
        hole[y0:y1, x0:x1] = 255
        plate = cv2.seamlessClone(patch, plate, hole, ((x0 + x1) // 2, (y0 + y1) // 2), cv2.NORMAL_CLONE)
    cv2.imwrite(str(root / "p88_plate.jpg"), plate, [cv2.IMWRITE_JPEG_QUALITY, 88])
    cv2.imwrite("/tmp/p88_mask.png", keep * 255)


if __name__ == "__main__":
    root = Path(sys.argv[1])
    lake(root)
    traffic(root)
