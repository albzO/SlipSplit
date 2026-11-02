"""Desktop diagnostic replay of Android boundary scoring on supplied screenshots.

These are derived photographs: the app's known dim overlay and handles are
approximately removed. They are not substitutes for original-photo validation.
"""
from pathlib import Path
import json

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
ATTACHMENTS = ROOT / ".codex-remote-attachments/01a197ce-364f-7a03-8c29-9700db41bef4/179d5077-813b-407c-8b7b-24c9ef896c0e"
CASES = [
    ("1-11769.jpg", [(63, 439), (369, 402), (414, 859), (197, 869)]),
    ("2-11768.jpg", [(63, 605), (525, 609), (525, 930), (62, 921)]),
]


def order(raw):
    points = np.asarray(raw, np.float64).reshape(4, 2)
    center = points.mean(axis=0)
    points = points[np.argsort(np.arctan2(points[:, 1] - center[1], points[:, 0] - center[0]))]
    return np.roll(points, -np.argmin(points.sum(axis=1)), axis=0)


def score(raw, contour_area, hsv, distances):
    points = order(raw)
    h, w = distances.shape
    area = cv2.contourArea(points.astype(np.float32))
    if not 0.025 <= area / (w*h) <= 1.001 or contour_area / area < 0.5:
        return None
    angle_quality = 0
    support_total = 0
    supported = physical_sides = contrast_sides = 0
    contrast_total = 0
    for i in range(4):
        a, b, c = points[i], points[(i+1) % 4], points[(i+2) % 4]
        u, v = b-a, c-b
        length, next_length = np.linalg.norm(u), np.linalg.norm(v)
        if u[0]*v[1] - u[1]*v[0] <= 0 or min(length, next_length) < min(w, h)*0.05:
            return None
        cosine = abs(np.dot(u, v)/(length*next_length))
        if cosine > .85:
            return None
        angle_quality += 1-cosine
        samples = np.array([a + u * j/33 for j in range(1, 33)])
        pix = np.rint(samples).astype(int)
        pix[:, 0] = np.clip(pix[:, 0], 0, w-1)
        pix[:, 1] = np.clip(pix[:, 1], 0, h-1)
        physical = np.mean(distances[pix[:, 1], pix[:, 0]] <= 7)
        physical_sides += physical >= .2
        clipped = np.mean((pix[:, 0] <= 2) | (pix[:, 0] >= w-3) |
                          (pix[:, 1] <= 2) | (pix[:, 1] >= h-3))
        side = max(physical, .45 if clipped >= 26/32 else 0)
        supported += side >= .2
        support_total += side
        inset = np.clip(min(w, h)*.012, 3, 10)
        normal = np.array([-u[1], u[0]])/length*inset
        inner = np.rint(samples + normal).astype(int)
        outer = np.rint(samples - normal).astype(int)
        valid = ((inner[:, 0] >= 0) & (inner[:, 0] < w) & (inner[:, 1] >= 0) & (inner[:, 1] < h) &
                 (outer[:, 0] >= 0) & (outer[:, 0] < w) & (outer[:, 1] >= 0) & (outer[:, 1] < h))
        if valid.sum() >= 8:
            inside = hsv[inner[valid, 1], inner[valid, 0]].astype(float)
            outside = hsv[outer[valid, 1], outer[valid, 0]].astype(float)
            sat = np.mean(outside[:, 1] - inside[:, 1])/80
            value = np.mean(inside[:, 2] - outside[:, 2])/100
            contrast = np.clip(max(sat, value), 0, 1)
            contrast_sides += contrast >= .10
            contrast_total += contrast
    if support_total/4 < .30 or supported < 3 or physical_sides < 2:
        return None
    samples = []
    for y in range(1, 25):
        t = y/25
        left = points[0]*(1-t) + points[3]*t
        right = points[1]*(1-t) + points[2]*t
        for x in range(1, 17):
            p = np.rint(left*(1-x/17) + right*x/17).astype(int)
            samples.append(hsv[np.clip(p[1], 0, h-1), np.clip(p[0], 0, w-1)])
    samples = np.array(samples, float)
    paper = np.mean((samples[:, 1] <= 70) & (samples[:, 2] >= 35))
    if paper < .65 or contrast_sides < 2:
        return None
    quality = (paper*.45 + contrast_total/4*.25 + support_total/4*.15 +
               angle_quality/4*.10 + np.sqrt(area/(w*h))*.05)
    return quality, points, {"paper": round(paper, 3), "contrast": round(contrast_total/4, 3)}


def detect(photo):
    gray = cv2.cvtColor(photo, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    hsv = cv2.cvtColor(photo, cv2.COLOR_BGR2HSV)
    edges = cv2.Canny(blurred, 20, 60)
    distances = cv2.distanceTransform(cv2.bitwise_not(edges), cv2.DIST_L2, 3)
    kernel = np.ones((5, 5), np.uint8)
    masks = [cv2.morphologyEx(edges, cv2.MORPH_CLOSE, kernel),
             cv2.morphologyEx(cv2.Canny(blurred, 60, 180), cv2.MORPH_CLOSE, kernel)]
    for flag in [cv2.THRESH_BINARY, cv2.THRESH_BINARY_INV]:
        masks.append(cv2.threshold(blurred, 0, 255, flag | cv2.THRESH_OTSU)[1])
    for saturation in [30, 50, 70]:
        mask = cv2.inRange(hsv, (0, 0, 35), (180, saturation, 255))
        masks.append(cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel))
    candidates = []
    for mask in masks:
        contours = cv2.findContours(mask, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)[0]
        ranked = sorted(((cv2.contourArea(c), c) for c in contours if cv2.contourArea(c) >= gray.size*.025),
                        key=lambda x: x[0], reverse=True)[:24]
        for area, contour in ranked:
            for outline in [contour, cv2.convexHull(contour)]:
                for epsilon in [.015, .03, .055]:
                    polygon = cv2.approxPolyDP(outline, cv2.arcLength(outline, True)*epsilon, True)
                    if len(polygon) == 4:
                        candidate = score(polygon, area, hsv, distances)
                        if candidate:
                            candidates.append(candidate)
    return sorted(candidates, key=lambda x: x[0], reverse=True)


def main():
    output = ROOT / "logs/edge-replay"
    output.mkdir(parents=True, exist_ok=True)
    reports = []
    previews = []
    for filename, polygon in CASES:
        screenshot = cv2.imread(str(ATTACHMENTS / filename))
        # Exact displayed photo rectangle in these 588x1280 screenshots.
        x0, y0, x1, y1 = 62, 315, 526, 933
        selection = np.zeros(screenshot.shape[:2], np.uint8)
        cv2.fillConvexPoly(selection, np.array(polygon, np.int32), 255)
        photo = screenshot[y0:y1, x0:x1].copy()
        selected = selection[y0:y1, x0:x1] > 0
        repaired = photo.astype(float)
        repaired[~selected] /= (1 - 136/255)
        repaired = np.clip(repaired, 0, 255).astype(np.uint8)
        overlay = np.zeros(screenshot.shape[:2], np.uint8)
        cv2.polylines(overlay, [np.array(polygon, np.int32)], True, 255, 7)
        for p in polygon:
            cv2.circle(overlay, p, 18, 255, -1)
        repaired = cv2.inpaint(repaired, overlay[y0:y1, x0:x1], 4, cv2.INPAINT_TELEA)
        candidates = detect(repaired)
        image = repaired.copy()
        padded = None
        if candidates:
            points = candidates[0][1]
            padded = []
            margin = min(image.shape[:2]) * .03
            for i in range(4):
                a, b, c = points[(i+3) % 4], points[i], points[(i+1) % 4]
                previous, following = b-a, c-b
                n1 = np.array([-previous[1], previous[0]]) / np.linalg.norm(previous)
                n2 = np.array([-following[1], following[0]]) / np.linalg.norm(following)
                determinant = n1[0]*n2[1] - n1[1]*n2[0]
                delta = margin * np.array([n1[1]-n2[1], n2[0]-n1[0]]) / determinant
                point = b + delta
                point[0] = np.clip(point[0], 0, image.shape[1]-1)
                point[1] = np.clip(point[1], 0, image.shape[0]-1)
                padded.append(point)
            padded = np.rint(padded).astype(np.int32)
            cv2.polylines(image, [padded], True, (0, 220, 0), 2)
        cv2.imwrite(str(output / filename.replace('.jpg', '-replay.png')), image)
        previews.append(image)
        reports.append({"screenshot": filename, "derived_only": True,
                        "best": None if not candidates else {"score": round(candidates[0][0], 3),
                            "points": candidates[0][1].round().astype(int).tolist(),
                            "padded_points": padded.tolist(), **candidates[0][2]},
                        "candidates": len(candidates)})
    cv2.imwrite(str(output / "comparison.png"), np.hstack(previews))
    (output / "report.json").write_text(json.dumps(reports, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(reports, ensure_ascii=False))


if __name__ == "__main__":
    main()
