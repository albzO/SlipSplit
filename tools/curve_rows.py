"""Straightens curved receipt rows using only OCR text boxes, no model.

Each long text box gives the slope of the printed row where it sits. A smooth slope field fitted
to those samples can be followed sideways to tell which row any point lies on. That row position
can replace box coordinates for parsing (straighten_lines) or drive a vertical remap (flatten).
"""
import cv2
import numpy as np

GRID_X = 7
GRID_Y = 13
SMOOTHNESS = 0.005
FIT_ROUNDS = 6
OUTLIER_SCALE = 1.5
MIN_SLOPE_NOISE = 0.01
MIN_BOXES = 6
LONG_BOX_RATIO = 2
TRACE_STEP = 8
MAP_STEP = 8
MIN_ROW_SPACING = 0.25
# Rows bending less than this share of a text height are left to the straight-line deskew.
MIN_BEND_HEIGHTS = 0.5


def slope_samples(lines):
    """One (x, y, slope, weight) per long box, plus the median text height."""
    samples, heights = [], []
    for line in lines:
        if not line.get("poly"):
            continue
        (x0, y0), (x1, y1), (x2, y2), (x3, y3) = line["poly"]
        run = (x1 - x0) + (x2 - x3)
        width = run / 2
        height = (np.hypot(x3 - x0, y3 - y0) + np.hypot(x2 - x1, y2 - y1)) / 2
        if height > 0:
            heights.append(height)
        if run > 0 and width >= LONG_BOX_RATIO * height:
            slope = ((y1 - y0) + (y2 - y3)) / run
            samples.append(((x0 + x1 + x2 + x3) / 4, (y0 + y1 + y2 + y3) / 4, slope, width))
    return np.array(samples, float).reshape(-1, 4), float(np.median(heights)) if heights else 0.0


def second_differences(nx, ny):
    rows = []
    for j in range(ny):
        for i in range(nx - 2):
            row = np.zeros(nx * ny)
            row[j * nx + i : j * nx + i + 3] = [1, -2, 1]
            rows.append(row)
    for i in range(nx):
        for j in range(ny - 2):
            row = np.zeros(nx * ny)
            row[[j * nx + i, (j + 1) * nx + i, (j + 2) * nx + i]] = [1, -2, 1]
            rows.append(row)
    return np.array(rows)


class SlopeField:
    """Row slope over the page, bilinear on a coarse grid, so a crease can show as a kink."""

    def __init__(self, width, height, nodes, reference_x):
        self.width, self.height = width, height
        self.nodes = nodes
        self.reference_x = reference_x

    def cells(self, x, y):
        gx = np.clip(x / self.width, 0, 1) * (GRID_X - 1)
        gy = np.clip(y / self.height, 0, 1) * (GRID_Y - 1)
        ix = np.minimum(gx.astype(int), GRID_X - 2)
        iy = np.minimum(gy.astype(int), GRID_Y - 2)
        return ix, iy, gx - ix, gy - iy

    def slope(self, x, y):
        ix, iy, fx, fy = self.cells(x, y)
        n = self.nodes
        at = lambda i, j: n[j * GRID_X + i]
        top = at(ix, iy) * (1 - fx) + at(ix + 1, iy) * fx
        bottom = at(ix, iy + 1) * (1 - fx) + at(ix + 1, iy + 1) * fx
        return top * (1 - fy) + bottom * fy

    def trace(self, x, y, target_x):
        """Follows the row through each (x, y) to target_x, returning y there."""
        x = np.array(x, float)
        y = np.array(y, float)
        steps = int(np.ceil(np.max(np.abs(target_x - x), initial=0) / TRACE_STEP)) or 1
        step = (target_x - x) / steps
        for _ in range(steps):
            middle = y + self.slope(x, y) * step / 2
            y = y + self.slope(x + step / 2, middle) * step
            x = x + step
        return y


def fit(lines, width, height):
    """Returns (field, bend in text heights), or (None, 0) with too little text to trust."""
    samples, text_height = slope_samples(lines)
    if len(samples) < MIN_BOXES or text_height <= 0:
        return None, 0.0
    x, y, slope, box_width = samples.T
    field = SlopeField(width, height, np.zeros(GRID_X * GRID_Y), float(np.median(x)))
    ix, iy, fx, fy = field.cells(x, y)
    design = np.zeros((len(samples), GRID_X * GRID_Y))
    rows = np.arange(len(samples))
    design[rows, iy * GRID_X + ix] = (1 - fx) * (1 - fy)
    design[rows, iy * GRID_X + ix + 1] = fx * (1 - fy)
    design[rows, (iy + 1) * GRID_X + ix] = (1 - fx) * fy
    design[rows, (iy + 1) * GRID_X + ix + 1] = fx * fy
    weights = box_width / np.median(box_width)
    smooth = second_differences(GRID_X, GRID_Y)
    penalty = smooth.T @ smooth
    robust = np.ones(len(samples))
    for _ in range(FIT_ROUNDS):
        w = weights * robust
        normal = (design.T * w) @ design + SMOOTHNESS * w.sum() * penalty
        # Tiny ridge: grid corners with no text and no curvature are otherwise undetermined.
        normal += 1e-6 * np.eye(len(normal))
        field.nodes = np.linalg.solve(normal, (design.T * w) @ slope)
        misses = np.abs(design @ field.nodes - slope)
        limit = max(OUTLIER_SCALE * 1.48 * np.median(misses), MIN_SLOPE_NOISE)
        robust = np.minimum(1, limit / np.maximum(misses, 1e-9))
    return field, bend(field, x, y) / text_height


def bend(field, x, y):
    """Largest distance a row departs from the straight line through its two text ends."""
    left, right = float(np.min(x)), float(np.max(x))
    if right <= left:
        return 0.0
    columns = np.linspace(left, right, 9)
    starts = np.quantile(y, np.linspace(0.05, 0.95, 15))
    shape = np.full(len(starts), left)
    start = field.trace(shape, starts, field.reference_x)
    traced = np.array([field.trace(np.full(len(start), field.reference_x), start, c) for c in columns])
    share = (columns - left) / (right - left)
    chord = np.outer(1 - share, traced[0]) + np.outer(share, traced[-1])
    return float(np.max(np.abs(traced - chord)))


def straighten_lines(lines, field):
    """Same OCR lines with corners moved to row coordinates, so parsing sees level rows."""
    level = []
    for line in lines:
        if not line.get("poly"):
            level.append(line)
            continue
        points = np.array(line["poly"], float)
        rows = field.trace(points[:, 0], points[:, 1], field.reference_x)
        poly = [[float(px), float(py)] for px, py in zip(points[:, 0], rows)]
        box = [int(points[:, 0].min()), int(rows.min()), int(points[:, 0].max()), int(rows.max())]
        level.append({**line, "poly": poly, "box": box})
    return level


def flatten(image, field):
    """Remaps the image so rows are level; None when rows would cross and the fit can't be used."""
    height, width = image.shape[:2]
    columns = np.arange(0, width + MAP_STEP, MAP_STEP, dtype=float)
    rows = np.arange(0, height + MAP_STEP, MAP_STEP, dtype=float)
    source = np.stack(
        [field.trace(np.full(len(rows), field.reference_x), rows, column) for column in columns], axis=1,
    )
    if np.min(np.diff(source, axis=0)) < MIN_ROW_SPACING * MAP_STEP:
        return None
    map_y = cv2.resize(source.astype(np.float32), (width, height), interpolation=cv2.INTER_LINEAR)
    map_x, _ = np.meshgrid(np.arange(width, dtype=np.float32), np.arange(height, dtype=np.float32))
    return cv2.remap(image, map_x, map_y, cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)


def draw_rows(image, field, step=60):
    """Debug overlay: the fitted rows drawn over the original photo."""
    picture = image.copy()
    height, width = image.shape[:2]
    columns = np.arange(0, width, 12, dtype=float)
    for row in np.arange(step / 2, height, step, dtype=float):
        ys = [field.trace(np.array([field.reference_x]), np.array([row]), c)[0] for c in columns]
        points = np.stack([columns, ys], axis=1).astype(np.int32)
        cv2.polylines(picture, [points], False, (0, 0, 255), 2, cv2.LINE_AA)
    return picture
