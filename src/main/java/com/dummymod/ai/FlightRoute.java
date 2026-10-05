package com.dummymod.ai;

import java.util.*;
import java.util.function.Predicate;

/** Bounded six-axis flight routing. Diagonal corner cutting is never allowed. */
public final class FlightRoute {
    public record Point(int x,int y,int z) {
        int distance(Point p){return Math.abs(x-p.x)+Math.abs(y-p.y)+Math.abs(z-p.z);}
    }
    private record Node(Point p,int cost,int score){}
    public static List<Point> find(Point start,Point goal,Predicate<Point> free,int limit) {
        if(!free.test(goal))return List.of();
        PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingInt(Node::score).thenComparingInt(Node::cost));
        Map<Point,Integer> cost=new HashMap<>();Map<Point,Point> previous=new HashMap<>();
        open.add(new Node(start,0,start.distance(goal)));cost.put(start,0);
        int[][] directions={{0,1,0},{1,0,0},{0,0,1},{-1,0,0},{0,0,-1},{0,-1,0}};
        int visited=0;
        while(!open.isEmpty() && visited++<limit) {
            Node n=open.remove();if(n.cost!=cost.getOrDefault(n.p,Integer.MAX_VALUE))continue;
            if(n.p.equals(goal)) {
                LinkedList<Point> path=new LinkedList<>();Point p=goal;
                while(!p.equals(start)){path.addFirst(p);p=previous.get(p);}return path;
            }
            for(int[] d:directions) {
                Point p=new Point(n.p.x+d[0],n.p.y+d[1],n.p.z+d[2]);
                if(p.distance(start)>start.distance(goal)+18 || cost.getOrDefault(p,Integer.MAX_VALUE)<=n.cost+1 || !free.test(p))continue;
                cost.put(p,n.cost+1);previous.put(p,n.p);open.add(new Node(p,n.cost+1,n.cost+1+p.distance(goal)));
            }
        }
        return List.of();
    }
}
