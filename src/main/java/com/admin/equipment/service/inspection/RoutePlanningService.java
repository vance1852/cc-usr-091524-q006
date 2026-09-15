package com.admin.equipment.service.inspection;

import com.admin.equipment.model.inspection.InspectionPoint;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RoutePlanningService {

    public static class RoutePoint {
        public Long pointId;
        public String code;
        public String name;
        public Double coordX;
        public Double coordY;
        public Integer sequence;

        public RoutePoint(Long pointId, String code, String name, Double coordX, Double coordY) {
            this.pointId = pointId;
            this.code = code;
            this.name = name;
            this.coordX = coordX;
            this.coordY = coordY;
        }
    }

    public static class RouteResult {
        public List<RoutePoint> orderedPoints;
        public Double totalDistance;
        public String algorithm;
        public List<double[]> segments = new ArrayList<>();

        public RouteResult(List<RoutePoint> orderedPoints, Double totalDistance, String algorithm) {
            this.orderedPoints = orderedPoints;
            this.totalDistance = totalDistance;
            this.algorithm = algorithm;
            for (int i = 0; i < orderedPoints.size() - 1; i++) {
                RoutePoint a = orderedPoints.get(i);
                RoutePoint b = orderedPoints.get(i + 1);
                segments.add(new double[]{a.coordX, a.coordY, b.coordX, b.coordY, distance(a, b)});
            }
        }
    }

    public static double distance(RoutePoint a, RoutePoint b) {
        if (a.coordX == null || a.coordY == null || b.coordX == null || b.coordY == null) {
            return 0.0;
        }
        double dx = a.coordX - b.coordX;
        double dy = a.coordY - b.coordY;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public double calculateTotalDistance(List<RoutePoint> points) {
        double total = 0.0;
        for (int i = 0; i < points.size() - 1; i++) {
            total += distance(points.get(i), points.get(i + 1));
        }
        return total;
    }

    public RouteResult planByCodeOrder(List<InspectionPoint> points) {
        List<InspectionPoint> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparing(InspectionPoint::getCode));
        List<RoutePoint> route = toRoutePoints(sorted);
        applySequence(route);
        double total = calculateTotalDistance(route);
        return new RouteResult(route, total, "sequential");
    }

    public RouteResult planOptimizedTSP(List<InspectionPoint> points) {
        List<RoutePoint> route = toRoutePoints(new ArrayList<>(points));
        if (route.size() <= 2) {
            applySequence(route);
            return new RouteResult(route, calculateTotalDistance(route), "optimized");
        }
        List<RoutePoint> nearest = nearestNeighbor(route);
        List<RoutePoint> optimized = twoOpt(nearest);
        applySequence(optimized);
        return new RouteResult(optimized, calculateTotalDistance(optimized), "optimized");
    }

    public RouteResult planOptimizedTSP(List<InspectionPoint> points, Long startPointId) {
        List<RoutePoint> route = toRoutePoints(new ArrayList<>(points));
        if (route.size() <= 2) {
            applySequence(route);
            return new RouteResult(route, calculateTotalDistance(route), "optimized");
        }
        List<RoutePoint> nearest = nearestNeighbor(route, startPointId);
        List<RoutePoint> optimized = twoOpt(nearest);
        applySequence(optimized);
        return new RouteResult(optimized, calculateTotalDistance(optimized), "optimized");
    }

    private List<RoutePoint> nearestNeighbor(List<RoutePoint> input) {
        return nearestNeighbor(input, null);
    }

    private List<RoutePoint> nearestNeighbor(List<RoutePoint> input, Long startPointId) {
        List<RoutePoint> remaining = new ArrayList<>(input);
        List<RoutePoint> result = new ArrayList<>();
        RoutePoint current;
        if (startPointId != null) {
            current = remaining.stream()
                    .filter(p -> p.pointId.equals(startPointId))
                    .findFirst()
                    .orElse(remaining.get(0));
        } else {
            current = remaining.get(0);
        }
        remaining.remove(current);
        result.add(current);
        while (!remaining.isEmpty()) {
            RoutePoint next = null;
            double minDist = Double.MAX_VALUE;
            for (RoutePoint p : remaining) {
                double d = distance(current, p);
                if (d < minDist) {
                    minDist = d;
                    next = p;
                }
            }
            result.add(next);
            remaining.remove(next);
            current = next;
        }
        return result;
    }

    private List<RoutePoint> twoOpt(List<RoutePoint> route) {
        List<RoutePoint> best = new ArrayList<>(route);
        double bestDist = calculateTotalDistance(best);
        boolean improved = true;
        int maxIter = 100;
        int n = best.size();
        while (improved && maxIter-- > 0) {
            improved = false;
            for (int i = 0; i < n - 2; i++) {
                for (int j = i + 1; j < n - 1; j++) {
                    List<RoutePoint> candidate = twoOptSwap(best, i, j);
                    double cDist = calculateTotalDistance(candidate);
                    if (cDist < bestDist - 0.0001) {
                        best = candidate;
                        bestDist = cDist;
                        improved = true;
                    }
                }
            }
        }
        return best;
    }

    private List<RoutePoint> twoOptSwap(List<RoutePoint> route, int i, int j) {
        List<RoutePoint> result = new ArrayList<>();
        for (int k = 0; k <= i - 1; k++) {
            result.add(route.get(k));
        }
        for (int k = j; k >= i; k--) {
            result.add(route.get(k));
        }
        for (int k = j + 1; k < route.size(); k++) {
            result.add(route.get(k));
        }
        return result;
    }

    private List<RoutePoint> toRoutePoints(List<InspectionPoint> points) {
        List<RoutePoint> result = new ArrayList<>();
        for (InspectionPoint p : points) {
            result.add(new RoutePoint(p.getId(), p.getCode(), p.getName(), p.getCoordX(), p.getCoordY()));
        }
        return result;
    }

    private void applySequence(List<RoutePoint> route) {
        for (int i = 0; i < route.size(); i++) {
            route.get(i).sequence = i + 1;
        }
    }
}
