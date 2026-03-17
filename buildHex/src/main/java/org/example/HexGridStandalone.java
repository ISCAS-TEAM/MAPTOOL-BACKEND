package org.example;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKTWriter;
import org.locationtech.jts.io.geojson.GeoJsonWriter;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import java.util.*;
import java.util.stream.Collectors;
public class HexGridStandalone {

    // ===================== 选择输入模式 =====================
    enum InputMode { UTM, WGS84 }   // UTM: 用 UTM_EPSG+UTM_CORNERS；WGS84: 用左上角+右下角经纬度
    static InputMode INPUT_MODE = InputMode.WGS84;
    // ======================================================

    // ===================== 参数：六边形边长（米） =====================
    static double EDGE_METERS = 50.0; // 太小格子多，建议先用 50/100 调试
    // ================================================================

    // ===================== 模式A：UTM 输入（保留原逻辑） =====================
    static String UTM_EPSG = "EPSG:32650";
    static List<Coordinate> UTM_CORNERS = List.of(
            new Coordinate(500000, 4640000),
            new Coordinate(502000, 4640000),
            new Coordinate(502000, 4638500),
            new Coordinate(500000, 4638500)
    );
    // ==================================================================

    // ===================== 模式B：WGS84 输入（【改动】只保留左上角+右下角） =====================
    // 左上角经纬度（lon, lat）
    static LonLat WGS84_TOP_LEFT = new LonLat(117.000000, 41.900000);

    // 右下角经纬度（lon, lat）
    static LonLat WGS84_BOTTOM_RIGHT = new LonLat(117.20, 41.70);

    // 【改动】WGS84 模式固定使用 EPSG:3857 平面来生成格网
    static final String WGS84_PLANAR_EPSG = "EPSG:3857";
    // ========================================================================================

    // 经纬度
    record LonLat(double lon, double lat) {}

    // 【改动】为方便同时兼容 UTM / 3857，这里不再叫 centerUtm / hexUtm，而改成 centerPlanar / hexPlanar
    record Cell(int c, int r, Coordinate centerPlanar, Polygon hexPlanar, Coordinate centerLonLat) {}

    // 【新增】矩形结构：左上角 + 右下角
    record RectWgs(LonLat tl, LonLat br) {}

    public static void main(String[] args) throws Exception {
        GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 0);

        // 统一准备：
        // planarEpsg      : 当前平面坐标系（UTM 模式下是 UTM；WGS84 模式下固定是 3857）
        // planarTag       : 仅用于输出文件名（utm / 3857）
        // regionPlanar    : 平面区域
        // anchorTopLeft   : (1,1) 的中心点
        // planarToWgs     : 平面 -> WGS84 转换
        String planarEpsg;
        String planarTag;
        Polygon regionPlanar;
        Coordinate anchorTopLeft;
        MathTransform planarToWgs;
        List<Cell> cells;

        // 【保留原逻辑】WGS84 基准 CRS
        CoordinateReferenceSystem wgs84 = CRS.decode("EPSG:4326", true);

        if (INPUT_MODE == InputMode.UTM) {
            // ===================== UTM 模式（尽量保留原逻辑） =====================
            planarEpsg = UTM_EPSG;
            planarTag = "utm";

            List<Coordinate> cornersPlanar = UTM_CORNERS;

            // UTM 模式：仍然在 UTM 里取左上角点作为锚点
            anchorTopLeft = pickTopLeftPlanar(cornersPlanar);

            // 区域（UTM平面多边形）
            regionPlanar = polygonFromUnorderedCorners(gf, cornersPlanar);

            // CRS transforms（UTM -> WGS84）
            CoordinateReferenceSystem planarCrs = CRS.decode(planarEpsg, true);
            planarToWgs = CRS.findMathTransform(planarCrs, wgs84, true);

            // 【保留原逻辑】UTM 模式仍然使用“相交即保留完整六边形”的旧逻辑
            cells = generateFullHexesOddR_PointyTop(
                    gf, regionPlanar, anchorTopLeft, EDGE_METERS, planarToWgs
            );

        } else {
            // ===================== WGS84 模式（【改动】关键修正版） =====================
            // 旧版问题：
            // 1. 先选 UTM 分带
            // 2. 把区域作为“投影后的普通 polygon”处理
            // 3. 再用 intersects 去筛六边形
            //
            // 这样在范围变大时，会出现：
            // - 第一行不对
            // - 越来越偏
            // - 看起来没碰到的地方也留下完整六边形
            //
            // 现在改为：
            // 1. 只认左上角 + 右下角
            // 2. 直接在 EPSG:3857 下构造严格矩形
            // 3. 只保留“中心点在矩形内”的完整六边形

            planarEpsg = WGS84_PLANAR_EPSG;
            planarTag = "3857";

            RectWgs rect = normalizeRect(WGS84_TOP_LEFT, WGS84_BOTTOM_RIGHT);

            CoordinateReferenceSystem planarCrs = CRS.decode(planarEpsg, true);
            MathTransform wgsToPlanar = CRS.findMathTransform(wgs84, planarCrs, true);
            planarToWgs = CRS.findMathTransform(planarCrs, wgs84, true);

            // 左上角 / 右下角投影到 3857
            Coordinate tlPlanar = JTS.transform(new Coordinate(rect.tl.lon, rect.tl.lat), null, wgsToPlanar);
            Coordinate brPlanar = JTS.transform(new Coordinate(rect.br.lon, rect.br.lat), null, wgsToPlanar);

            double xMin = tlPlanar.x;
            double xMax = brPlanar.x;
            double yMax = tlPlanar.y;
            double yMin = brPlanar.y;

            // 【改动】不再把区域当成任意 polygon，而是明确构造成矩形
            regionPlanar = rectanglePolygon(gf, xMin, yMin, xMax, yMax);

            // 【改动】(1,1) 的中心 = 左上角点投影到 3857 后的位置1
            anchorTopLeft = tlPlanar;

            // 【改动】WGS84 模式使用严格矩形版格网生成函数
            cells = generateFullHexesOddR_PointyTop_Rect(
                    gf, xMin, yMin, xMax, yMax, anchorTopLeft, EDGE_METERS, planarToWgs
            );

            System.out.println("Anchor (WGS84 top-left) = " + rect.tl);
            System.out.println("Bottom-right (WGS84)    = " + rect.br);
        }

        System.out.println("INPUT_MODE: " + INPUT_MODE);
        System.out.println("Chosen/Used planar EPSG: " + planarEpsg);
        System.out.println("Hex edge(m): " + EDGE_METERS);
        System.out.println("Anchor center (planar) as (1,1): " + fmt(anchorTopLeft));
        System.out.println("Full hexes: " + cells.size());

        // 输出：WKT
        writeCellsToWkt("hex_cells_" + planarTag + "_full.wkt", cells, true, planarToWgs);
        writeCellsToWkt("hex_cells_wgs84_full.wkt", cells, false, planarToWgs);

        // 输出：CSV（中心点 + 行列号 + 平面坐标 + lon/lat）
        writeCentersToCsv("hex_centers.csv", cells);

        // 输出：CSV（行列号 + lon/lat）
        writeCRLonLatCsv("cr_lonlat.csv", cells);

        // 输出：GeoJSON（区域 + 格网，平面/WGS84 各一份）
        writeGeometryGeoJson("region_" + planarTag + ".geojson", regionPlanar, planarEpsg);
        Geometry regionWgs = JTS.transform(regionPlanar, planarToWgs);
        writeGeometryGeoJson("region_wgs84.geojson", regionWgs, "EPSG:4326");

        writeCellsGeoJson("hex_" + planarTag + "_full.geojson", cells, false, planarToWgs, planarEpsg);
        writeCellsGeoJson("hex_wgs84_full.geojson", cells, true, planarToWgs, "EPSG:4326");

        // 自检：(1,1) 的中心必须等于锚点
        cells.stream().filter(c -> c.c == 1 && c.r == 1).findFirst().ifPresent(c -> {
            double d = c.centerPlanar.distance(anchorTopLeft);
            System.out.println("Anchor check distance for (1,1) (should ~0): " + d);
        });

        System.out.println("Done. Outputs:");
        System.out.println(" - region_" + planarTag + ".geojson / region_wgs84.geojson");
        System.out.println(" - hex_" + planarTag + "_full.geojson / hex_wgs84_full.geojson");
        System.out.println(" - hex_cells_" + planarTag + "_full.wkt / hex_cells_wgs84_full.wkt");
        System.out.println(" - hex_centers.csv");
        System.out.println(" - cr_lonlat.csv");
    }

    /**
     * 【保留原逻辑】
     * UTM 模式继续使用旧版生成方式：
     * - 先按 envelope 扩展范围
     * - 再用 hex.intersects(regionPlanar) 过滤
     * - 相交即保留完整六边形
     *
     * 这段逻辑保留，是为了方便和原代码对照。
     */
    static List<Cell> generateFullHexesOddR_PointyTop(
            GeometryFactory gf,
            Polygon regionPlanar,
            Coordinate anchorTopLeft,
            double a,
            MathTransform planarToWgs
    ) throws Exception {

        final double width = Math.sqrt(3) * a;   // 列距
        final double rowStep = 1.5 * a;          // 行距（向下）

        Envelope env = regionPlanar.getEnvelopeInternal();
        double pad = 4 * a; // 稍微加大 padding，避免边界漏格
        Envelope padded = new Envelope(env.getMinX() - pad, env.getMaxX() + pad,
                env.getMinY() - pad, env.getMaxY() + pad);

        final double x0 = anchorTopLeft.x;
        final double y0 = anchorTopLeft.y;

        int rMin = 1;
        int rMax = (int) Math.ceil((y0 - padded.getMinY()) / rowStep) + 3;

        List<Cell> out = new ArrayList<>();

        for (int r = rMin; r <= rMax; r++) {
            double shiftX = (((r - 1) & 1) == 1) ? (width / 2.0) : 0.0;
            double y = y0 - (r - 1) * rowStep;

            double cMinD = 1 + (padded.getMinX() - x0 - shiftX) / width;
            double cMaxD = 1 + (padded.getMaxX() - x0 - shiftX) / width;

            int cMin = (int) Math.floor(cMinD) - 3;
            int cMax = (int) Math.ceil(cMaxD) + 3;

            for (int c = cMin; c <= cMax; c++) {
                double x = x0 + (c - 1) * width + shiftX;
                Coordinate center = new Coordinate(x, y);

                Polygon hex = hexPolygonPointyTop(gf, center, a);

                // 【保留原逻辑】相交即保留完整六边形
                if (!hex.intersects(regionPlanar)) continue;

                Coordinate centerLonLat = JTS.transform(center, null, planarToWgs);
                out.add(new Cell(c, r, center, hex, centerLonLat));
            }
        }

        // 若 c<=0，则整体重编号到从 1 开始
        int minC = out.stream().mapToInt(Cell::c).min().orElse(1);
        if (minC < 1) {
            int shift = 1 - minC;
            out = out.stream()
                    .map(cell -> new Cell(cell.c + shift, cell.r, cell.centerPlanar, cell.hexPlanar, cell.centerLonLat))
                    .collect(Collectors.toList());
            System.out.println("[INFO] Column reindex applied: +" + shift + " (to start from 1).");
        }

        return out;
    }

    /**
     * 【新增 / 关键改动】
     * WGS84 模式专用：在 3857 的“严格矩形”里生成格网。
     *
     * 和旧版的区别：
     * 1. 不再用 polygon envelope + intersects 来控制边界
     * 2. 直接使用矩形边界 xMin/xMax/yMin/yMax
     * 3. 只保留“中心点落在矩形内”的完整六边形
     *
     * 这样可以修正：
     * - 第一行没生成对
     * - 大范围下越来越偏离矩形
     * - 没碰到的地方也画出完整六边形
     */
    static List<Cell> generateFullHexesOddR_PointyTop_Rect(
            GeometryFactory gf,
            double xMin,
            double yMin,
            double xMax,
            double yMax,
            Coordinate anchorTopLeft,
            double a,
            MathTransform planarToWgs
    ) throws Exception {

        final double width = Math.sqrt(3) * a;   // 相邻列中心水平距离
        final double rowStep = 1.5 * a;          // 相邻行中心竖向距离

        final double x0 = anchorTopLeft.x;       // (1,1) 中心
        final double y0 = anchorTopLeft.y;

        List<Cell> out = new ArrayList<>();

        // 行数：从顶往下，直到中心点落到矩形底边以下
        int rMax = (int) Math.floor((y0 - yMin) / rowStep) + 1;

        for (int r = 1; r <= rMax; r++) {
            double shiftX = (((r - 1) & 1) == 1) ? (width / 2.0) : 0.0;
            double y = y0 - (r - 1) * rowStep;

            // 本行列数：直接按右边界精确算，不再“多算一圈再 intersects 过滤”
            int cMax = (int) Math.floor((xMax - x0 - shiftX) / width) + 1;

            for (int c = 1; c <= cMax; c++) {
                double x = x0 + (c - 1) * width + shiftX;
                Coordinate center = new Coordinate(x, y);

                // 【关键改动】只保留中心点仍在矩形内的完整六边形
                if (x < xMin || x > xMax || y < yMin || y > yMax) {
                    continue;
                }

                Polygon hex = hexPolygonPointyTop(gf, center, a);
                Coordinate centerLonLat = JTS.transform(center, null, planarToWgs);

                out.add(new Cell(c, r, center, hex, centerLonLat));
            }
        }

        return out;
    }

    // 尖朝上 pointy-top 六边形（保留原逻辑）
    static Polygon hexPolygonPointyTop(GeometryFactory gf, Coordinate center, double a) {
        Coordinate[] pts = new Coordinate[7];
        for (int i = 0; i < 6; i++) {
            double deg = 90.0 - 60.0 * i; // 90,30,-30,-90,-150,150
            double rad = Math.toRadians(deg);
            pts[i] = new Coordinate(center.x + a * Math.cos(rad), center.y + a * Math.sin(rad));
        }
        pts[6] = new Coordinate(pts[0]);
        return gf.createPolygon(pts);
    }

    // ====== WGS84 -> UTM（自动选带，保留给 UTM 模式用） ======

    static String chooseUtmEpsg(List<LonLat> corners) {
        LonLat cen = centroidLonLat(corners);
        double lon = normalizeLon(cen.lon);
        double lat = cen.lat;

        int zone = (int) Math.floor((lon + 180.0) / 6.0) + 1; // 1..60
        zone = Math.max(1, Math.min(60, zone));
        int base = (lat >= 0.0) ? 32600 : 32700;

        Set<Integer> zones = new HashSet<>();
        for (LonLat p : corners) {
            int z = (int) Math.floor((normalizeLon(p.lon) + 180.0) / 6.0) + 1;
            zones.add(z);
        }
        if (zones.size() > 1) {
            System.out.println("[WARN] Corners span multiple UTM zones: " + zones +
                    ". Using centroid zone=" + zone + ". For larger areas consider other projections.");
        }

        return "EPSG:" + (base + zone);
    }

    static LonLat centroidLonLat(List<LonLat> pts) {
        double lon = 0, lat = 0;
        for (LonLat p : pts) { lon += p.lon; lat += p.lat; }
        return new LonLat(lon / pts.size(), lat / pts.size());
    }

    static double normalizeLon(double lon) {
        double x = lon % 360.0;
        if (x >= 180.0) x -= 360.0;
        if (x < -180.0) x += 360.0;
        return x;
    }

    // WGS84 左上角（保留给 UTM 模式用）
    static LonLat pickTopLeftWgs84(List<LonLat> corners) {
        return corners.stream()
                .max(Comparator.<LonLat>comparingDouble(p -> p.lat)
                        .thenComparingDouble(p -> -p.lon))
                .orElseThrow();
    }

    static List<Coordinate> lonLatCornersToUtm(List<LonLat> cornersWgs84, String utmEpsg) throws Exception {
        CoordinateReferenceSystem wgs84 = CRS.decode("EPSG:4326", true);
        CoordinateReferenceSystem utm = CRS.decode(utmEpsg, true);
        MathTransform toUtm = CRS.findMathTransform(wgs84, utm, true);

        List<Coordinate> out = new ArrayList<>(cornersWgs84.size());
        for (LonLat p : cornersWgs84) {
            Coordinate c = new Coordinate(p.lon, p.lat);
            Coordinate utmC = JTS.transform(c, null, toUtm);
            out.add(utmC);
        }
        return out;
    }

    static Coordinate lonLatToUtm(LonLat p, String utmEpsg) throws Exception {
        CoordinateReferenceSystem wgs84 = CRS.decode("EPSG:4326", true);
        CoordinateReferenceSystem utm = CRS.decode(utmEpsg, true);
        MathTransform toUtm = CRS.findMathTransform(wgs84, utm, true);
        return JTS.transform(new Coordinate(p.lon, p.lat), null, toUtm);
    }

    // ====== 构造区域多边形（4点无序，保留给 UTM 模式用） ======
    static Polygon polygonFromUnorderedCorners(GeometryFactory gf, List<Coordinate> corners) {
        if (corners.size() != 4) throw new IllegalArgumentException("Need exactly 4 corners.");

        Coordinate centroid = new Coordinate(
                corners.stream().mapToDouble(c -> c.x).average().orElseThrow(),
                corners.stream().mapToDouble(c -> c.y).average().orElseThrow()
        );

        List<Coordinate> sorted = corners.stream()
                .sorted(Comparator.comparingDouble(c -> Math.atan2(c.y - centroid.y, c.x - centroid.x)))
                .collect(Collectors.toList());

        Coordinate[] ring = new Coordinate[5];
        for (int i = 0; i < 4; i++) ring[i] = new Coordinate(sorted.get(i));
        ring[4] = new Coordinate(ring[0]);
        return gf.createPolygon(ring);
    }

    // UTM 平面左上角（保留给 UTM 模式用）
    static Coordinate pickTopLeftPlanar(List<Coordinate> cornersPlanar) {
        return cornersPlanar.stream()
                .max(Comparator.<Coordinate>comparingDouble(c -> c.y).thenComparingDouble(c -> -c.x))
                .orElseThrow();
    }

    // 【新增】WGS84 模式专用：规范化左上 / 右下
    static RectWgs normalizeRect(LonLat p1, LonLat p2) {
        double latTop = Math.max(p1.lat, p2.lat);
        double latBottom = Math.min(p1.lat, p2.lat);

        double lonLeft = Math.min(normalizeLon(p1.lon), normalizeLon(p2.lon));
        double lonRight = Math.max(normalizeLon(p1.lon), normalizeLon(p2.lon));

        return new RectWgs(new LonLat(lonLeft, latTop), new LonLat(lonRight, latBottom));
    }

    // 【新增】WGS84 模式专用：直接构造严格矩形 polygon
    static Polygon rectanglePolygon(GeometryFactory gf, double xMin, double yMin, double xMax, double yMax) {
        Coordinate[] ring = new Coordinate[] {
                new Coordinate(xMin, yMax), // TL
                new Coordinate(xMax, yMax), // TR
                new Coordinate(xMax, yMin), // BR
                new Coordinate(xMin, yMin), // BL
                new Coordinate(xMin, yMax)
        };
        return gf.createPolygon(ring);
    }

    // ====== 输出：WKT ======
    static void writeCellsToWkt(String filename, List<Cell> cells, boolean outputPlanar, MathTransform planarToWgs) throws Exception {
        WKTWriter wkt = new WKTWriter();
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(filename))) {
            for (Cell c : cells) {
                Geometry g = outputPlanar ? c.hexPlanar : JTS.transform(c.hexPlanar, planarToWgs);
                bw.write(wkt.write(g));
                bw.newLine();
            }
        }
    }

    // ====== 输出：CSV（中心点 + 行列号 + 平面坐标 + lon/lat） ======
    static void writeCentersToCsv(String filename, List<Cell> cells) throws Exception {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(filename))) {
            bw.write("c,r,center_x_planar,center_y_planar,center_lon,center_lat");
            bw.newLine();
            for (Cell cell : cells) {
                bw.write(String.format(Locale.ROOT, "%d,%d,%.3f,%.3f,%.8f,%.8f",
                        cell.c, cell.r,
                        cell.centerPlanar.x, cell.centerPlanar.y,
                        cell.centerLonLat.x, cell.centerLonLat.y
                ));
                bw.newLine();
            }
        }
    }

    // ====== 输出：行列号 + lon/lat（中心点）精简 CSV ======
    static void writeCRLonLatCsv(String filename, List<Cell> cells) throws Exception {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(filename))) {
            bw.write("c,r,lon,lat");
            bw.newLine();

            List<Cell> sorted = new ArrayList<>(cells);
            sorted.sort(Comparator.<Cell>comparingInt(cell -> cell.r).thenComparingInt(cell -> cell.c));

            for (Cell cell : sorted) {
                bw.write(String.format(Locale.ROOT, "%d,%d,%.8f,%.8f",
                        cell.c, cell.r,
                        cell.centerLonLat.x, cell.centerLonLat.y
                ));
                bw.newLine();
            }
        }
    }

    static String fmt(Coordinate c) {
        return String.format(Locale.ROOT, "(%.3f, %.3f)", c.x, c.y);
    }

    static String fmtLonLat(Coordinate c) {
        return String.format(Locale.ROOT, "(lon=%.8f, lat=%.8f)", c.x, c.y);
    }

    // ===== GeoJSON 输出 =====
    static void writeGeometryGeoJson(String filename, Geometry geom, String epsg) throws Exception {
        GeoJsonWriter gw = new GeoJsonWriter();
        String geomJson = gw.write(geom);

        StringBuilder sb = new StringBuilder(geomJson.length() + 256);
        sb.append("{\"type\":\"FeatureCollection\",")
                .append("\"name\":\"").append(escapeJson(filename)).append("\",")
                .append("\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"")
                .append(escapeJson(epsg))
                .append("\"}},")
                .append("\"features\":[")
                .append("{\"type\":\"Feature\",\"properties\":{},\"geometry\":")
                .append(geomJson)
                .append("}")
                .append("]}");

        Files.writeString(Paths.get(filename), sb.toString(), StandardCharsets.UTF_8);
    }

    static void writeCellsGeoJson(String filename,
                                  List<Cell> cells,
                                  boolean outputWgs84,
                                  MathTransform planarToWgs,
                                  String epsg) throws Exception {
        GeoJsonWriter gw = new GeoJsonWriter();
        StringBuilder sb = new StringBuilder(1024 * 1024);

        sb.append("{\"type\":\"FeatureCollection\",")
                .append("\"name\":\"").append(escapeJson(filename)).append("\",")
                .append("\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"")
                .append(escapeJson(epsg))
                .append("\"}},")
                .append("\"features\":[");

        boolean first = true;
        for (Cell cell : cells) {
            Geometry g = outputWgs84 ? JTS.transform(cell.hexPlanar, planarToWgs) : cell.hexPlanar;
            String geomJson = gw.write(g);

            if (!first) sb.append(",");
            first = false;

            sb.append("{\"type\":\"Feature\",")
                    .append("\"properties\":{")
                    .append("\"c\":").append(cell.c).append(",")
                    .append("\"r\":").append(cell.r).append(",")
                    .append("\"center_x\":").append(formatDouble(outputWgs84 ? cell.centerLonLat.x : cell.centerPlanar.x)).append(",")
                    .append("\"center_y\":").append(formatDouble(outputWgs84 ? cell.centerLonLat.y : cell.centerPlanar.y))
                    .append("},")
                    .append("\"geometry\":").append(geomJson)
                    .append("}");
        }

        sb.append("]}");
        Files.writeString(Paths.get(filename), sb.toString(), StandardCharsets.UTF_8);
    }

    static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String formatDouble(double v) {
        return String.format(Locale.ROOT, "%.8f", v);
    }
}