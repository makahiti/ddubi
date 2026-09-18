package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.model.Coordinate;
import java.util.*;

/** 수도권 WGS84 경로를 국소 평면(m)에 투영한다. 자기 교차에서는 모든 투영 후보를 유지한다.
 * 가장 가까운 한 점으로 방향을 확정하지 않으며 원본 경로를 변경하지 않는다. */
final class BusTrace {
    private record Edge(double x,double y,double dx,double dy,double length,double offset) { }
    private final Coordinate origin;
    private final double scale;
    private final List<Edge> edges=new ArrayList<>();
    final double length;
    BusTrace(List<Coordinate> points) {
        origin=points.get(0);
        scale=6371000*Math.cos(Math.toRadians(points.stream().mapToDouble(Coordinate::latitude).average().orElseThrow()));
        double total=0;
        for(int i=1;i<points.size();i++) {
            double x=x(points.get(i-1)),y=y(points.get(i-1));
            double dx=x(points.get(i))-x,dy=y(points.get(i))-y,l=Math.hypot(dx,dy);
            if(l>1e-6) {edges.add(new Edge(x,y,dx,dy,l,total));total+=l;}
        }
        length=total;
    }
    private double x(Coordinate p) {return Math.toRadians(p.longitude()-origin.longitude())*scale;}
    private double y(Coordinate p) {return Math.toRadians(p.latitude()-origin.latitude())*6371000;}
    List<Double> projections(Coordinate p,double radius) {
        List<Double> result=new ArrayList<>(); double px=x(p),py=y(p);
        for(Edge e:edges) {
            double t=Math.max(0,Math.min(1,((px-e.x)*e.dx+(py-e.y)*e.dy)/(e.length*e.length)));
            if(Math.hypot(px-e.x-t*e.dx,py-e.y-t*e.dy)<=radius) result.add(e.offset+t*e.length);
        }
        result.sort(Double::compare);return result;
    }
    List<Coordinate> clip(double start,double end) {
        List<Coordinate> result=new ArrayList<>();
        for(Edge e:edges) {
            if(e.offset+e.length<start || e.offset>end)continue;
            for(double pos:new double[]{Math.max(start,e.offset),Math.min(end,e.offset+e.length)}) {
                double t=(pos-e.offset)/e.length;
                Coordinate p=new Coordinate(origin.longitude()+Math.toDegrees((e.x+t*e.dx)/scale),
                        origin.latitude()+Math.toDegrees((e.y+t*e.dy)/6371000));
                if(result.isEmpty() || !result.get(result.size()-1).equals(p))result.add(p);
            }
        }
        return result;
    }
    static boolean valid(Coordinate p) {
        return p!=null && Double.isFinite(p.longitude()) && Double.isFinite(p.latitude())
                && p.longitude()>=125 && p.longitude()<=129 && p.latitude()>=36 && p.latitude()<=39;
    }
    static double distance(Coordinate a,Coordinate b) {
        double dlat=Math.toRadians(b.latitude()-a.latitude()),dlon=Math.toRadians(b.longitude()-a.longitude());
        double h=Math.pow(Math.sin(dlat/2),2)+Math.cos(Math.toRadians(a.latitude()))*Math.cos(Math.toRadians(b.latitude()))*Math.pow(Math.sin(dlon/2),2);
        h=Math.max(0,Math.min(1,h));return 12742000*Math.atan2(Math.sqrt(h),Math.sqrt(1-h));
    }
    /** 꼭짓점과 40m 이하 간격의 내부점을 비교한다. 표본 사이의 도로 동일성을 보장하지 않는다. */
    static List<Coordinate> samples(List<Coordinate> points) {
        List<Coordinate> out=new ArrayList<>();out.add(points.get(0));
        for(int i=1;i<points.size();i++) {
            Coordinate a=points.get(i-1),b=points.get(i);int n=Math.max(1,(int)Math.ceil(distance(a,b)/40));
            for(int j=1;j<=n;j++)out.add(new Coordinate(a.longitude()+(b.longitude()-a.longitude())*j/n,a.latitude()+(b.latitude()-a.latitude())*j/n));
        }
        return out;
    }
}
